package com.voicecaption.pipeline

import com.voicecaption.asr.RecognizedText
import com.voicecaption.asr.SpeechRecognizer
import com.voicecaption.asr.isAutoLanguage
import com.voicecaption.caption.CaptionStabilizer
import com.voicecaption.core.Caption
import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.PcmFrame
import com.voicecaption.core.SpeechSegment
import com.voicecaption.translate.Translator
import com.voicecaption.translate.TranslatorNotReadyException
import com.voicecaption.vad.VadSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Problems the UI may want to surface. The pipeline keeps running after each of them. */
sealed interface PipelineError {
    /**
     * The language packs for the configured pair are missing. Emitted once per outage: again only
     * after a translation has succeeded in between, so a missing pack does not flood the UI.
     */
    data object TranslatorNotReady : PipelineError

    /**
     * Speech recognition of one segment failed; that segment is skipped. [segmentsRecognizedBefore]
     * is how many segments the recognizer had handled successfully when this one failed; it is the
     * success signal for counting consecutive failures (it changes only after a success).
     */
    data class Recognizer(val cause: Throwable, val segmentsRecognizedBefore: Long = 0) : PipelineError

    /** Translation of one segment failed for a reason other than a missing pack; its source caption stays. */
    data class Translator(val cause: Throwable) : PipelineError
}

/**
 * Running counters for diagnosing latency and missed speech. All durations are milliseconds.
 * [dropped] counts segments evicted from a full queue before recognition (speech never captioned).
 */
data class PipelineStats(
    val cut: Long = 0,
    val dropped: Long = 0,
    val recognized: Long = 0,
    val queueDepth: Int = 0,
    val lastSegmentMs: Long = 0,
    val lastDecodeMs: Long = 0,
    val lastTranslateMs: Long = 0,
    /** From the segment being queued (speech already over) to its caption being final. */
    val lastLagMs: Long = 0,
    val maxLagMs: Long = 0,
    /** What the recognizer returned for the last segment (blank = empty). */
    val lastRaw: String = "",
    val lastNoise: Boolean = false,
    /** Language of the last recognized text when known (the detected one for auto-detect). */
    val lastLang: String = "",
)

/**
 * Audio frames in, live captions out: VAD segmentation -> recognition -> translation -> stabilizer.
 *
 * Concurrency model:
 * - [onFrame] and [stop] may be called from any thread (typically the audio thread and the UI);
 *   they serialize on a lock around the non-thread-safe [VadSegmenter]. [onFrame] never blocks on
 *   recognition: it only does a non-suspending `trySend` into a bounded channel.
 * - The channel has capacity 4 with DROP_OLDEST, so if recognition is slower than speech, the
 *   oldest queued segments are silently dropped and latency stays bounded. The newest speech is
 *   never the one dropped. This is intentional.
 * - One worker coroutine on [scope] takes segments in order, so captions never reorder.
 * - A ticker coroutine on [scope] calls [CaptionStabilizer.expire] every [tickMs]. The pipeline
 *   owns expiry; callers do not touch the stabilizer.
 * - The worker and ticker share the stabilizer, so every access goes through [mutex].
 *
 * Failures: recognizer and translator exceptions are reported on [errors] and never stop the
 * worker; [CancellationException] is rethrown and not reported. Settings are read via [settings]
 * once per segment, so a language change applies from the next segment.
 *
 * Caption ids are the segmenter's utterance numbers, increasing per segment (ids of segments
 * dropped from a full channel are simply never shown). A partial snapshot of a segment still being
 * spoken shares its id with the final segment, so the final text replaces the provisional one in
 * place; only the newest partial of an utterance is ever decoded.
 */
class CaptionPipeline(
    private val segmenter: VadSegmenter,
    private val recognizer: SpeechRecognizer,
    private val translator: Translator,
    private val stabilizer: CaptionStabilizer,
    private val settings: () -> CaptionSettings,
    scope: CoroutineScope,
    private val clock: () -> Long,
    tickMs: Long = 500,
    /** Caption shown while the recognizer hears only music (kept in one caption while it lasts). */
    private val musicLabel: String = "(music)",
) {
    private class Job(val id: Long, val seq: Long, val segment: SpeechSegment, val queuedAtMs: Long)

    private val _captions = MutableStateFlow<List<Caption>>(emptyList())

    /** Captions currently visible, oldest to newest. */
    val captions: StateFlow<List<Caption>> = _captions.asStateFlow()

    private val _errors = MutableSharedFlow<PipelineError>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Non-replaying error stream; collect it before feeding audio. Slow collectors lose old errors. */
    val errors: SharedFlow<PipelineError> = _errors.asSharedFlow()

    private val _stats = MutableStateFlow(PipelineStats())

    /** Live counters; cheap to collect. */
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private val dropCount = java.util.concurrent.atomic.AtomicLong()
    private val pending = java.util.concurrent.atomic.AtomicInteger()

    private fun bump(block: (PipelineStats) -> PipelineStats) = _stats.update(block)

    private val lock = Any() // guards segmenter, stopped, nextId
    private var stopped = false
    private var nextId = 0L

    private val mutex = Mutex() // guards stabilizer and publication to _captions
    private val queue = Channel<Job>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST) { job ->
        pending.decrementAndGet()
        if (!job.segment.partial) { // a dropped partial costs nothing: its final supersedes it
            newestSeq.remove(job.id)
            val d = dropCount.incrementAndGet()
            bump { it.copy(dropped = d, queueDepth = pending.get()) }
        }
    }
    private val newestSeq = java.util.concurrent.ConcurrentHashMap<Long, Long>() // utterance -> newest queued seq
    private var notReadyReported = false // worker-only
    private var segmentsRecognized = 0L // worker-only; successful transcribe() calls, noise included
    private var musicCaptionId: Long? = null // worker-only; the caption currently showing [musicLabel]

    private val ticker = scope.launch {
        while (isActive) {
            delay(tickMs)
            update { expire(clock()) }
        }
    }

    private val worker = scope.launch {
        for (job in queue) process(job)
    }.also { it.invokeOnCompletion { ticker.cancel() } }

    /** Feeds one 32 ms frame. Ignored after [stop]. */
    fun onFrame(frame: PcmFrame) {
        synchronized(lock) {
            if (stopped) return
            for (segment in segmenter.push(frame)) enqueue(segment)
        }
    }

    /**
     * Ends the session. Idempotent. Flushes the segmenter tail into the same queue, closes it, and
     * lets the worker drain everything already queued (so the last words are captioned) before the
     * ticker is cancelled. Frames fed afterwards are ignored. Captions on screen at that point are
     * left as they are, since expiry has stopped; callers should discard them with the pipeline.
     * If the scope itself is cancelled first, pending segments are abandoned.
     */
    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            segmenter.flush()?.let(::enqueue)
            queue.close()
        }
    }

    private fun enqueue(segment: SpeechSegment) {
        pending.incrementAndGet()
        val seq = nextId++
        newestSeq[segment.utterance] = seq
        queue.trySend(Job(segment.utterance, seq, segment, clock())) // DROP_OLDEST: only fails once closed, which stop() guards
        if (!segment.partial) {
            bump { it.copy(cut = it.cut + 1, queueDepth = pending.get(), lastSegmentMs = segment.endMs - segment.startMs) }
        }
    }

    private suspend fun update(block: CaptionStabilizer.() -> List<Caption>) {
        mutex.withLock { _captions.value = stabilizer.block() }
    }

    private suspend fun process(job: Job) {
        pending.decrementAndGet()
        // A partial is only worth decoding while it is still the newest snapshot of its utterance.
        if (job.segment.partial && newestSeq[job.id] != job.seq) return
        if (!job.segment.partial) newestSeq.remove(job.id)
        val s = settings()
        val decodeStart = clock()
        val transcript = try {
            recognizer.recognize(job.segment, s.sourceLang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(PipelineError.Recognizer(e, segmentsRecognized))
            return
        }
        segmentsRecognized++
        val decodeMs = clock() - decodeStart
        val text = transcript.text
        val cleaned = RecognizedText.stripSoundTags(text)
        val noise = RecognizedText.isNoise(cleaned)
        // The language of the text we hold: what the recognizer already outputs, else the spoken
        // language (the chosen one, or the detected one for auto-detect; null while still unknown).
        val textLang = recognizer.outputLanguage ?: if (isAutoLanguage(s.sourceLang)) transcript.language else s.sourceLang
        bump {
            it.copy(
                recognized = segmentsRecognized, queueDepth = pending.get(), lastDecodeMs = decodeMs,
                lastRaw = text.trim(), lastNoise = noise, lastLang = textLang.orEmpty(),
            )
        }
        if (RecognizedText.isMusic(transcript.event, text)) {
            showMusic(job)
            return
        }
        if (noise) return
        val shown = cleaned.trim()

        if (textLang == null) { // auto-detect has not decided yet: show the original, translate later
            update { onSourceText(job.id, shown, clock()) }
            recordLag(job, 0)
            return
        }
        if (sameLanguage(textLang, s.targetLang)) {
            update {
                onSourceText(job.id, shown, clock())
                onTranslated(job.id, shown, clock())
            }
            recordLag(job, 0)
            return
        }
        update { onSourceText(job.id, shown, clock()) }
        try {
            val translateStart = clock()
            val translated = translator.translate(shown, textLang, s.targetLang)
            recordLag(job, clock() - translateStart)
            notReadyReported = false
            // The source caption may have expired or been evicted while translating; a late
            // translation must not resurrect it as a source-less caption.
            mutex.withLock {
                if (_captions.value.any { it.id == job.id }) {
                    _captions.value = stabilizer.onTranslated(job.id, translated, clock())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslatorNotReadyException) {
            if (!notReadyReported) {
                notReadyReported = true
                _errors.tryEmit(PipelineError.TranslatorNotReady)
            }
        } catch (e: Exception) {
            _errors.tryEmit(PipelineError.Translator(e))
        }
    }

    /** One "(music)" caption for as long as the music lasts: a following music segment refreshes it. */
    private suspend fun showMusic(job: Job) {
        mutex.withLock {
            val id = musicCaptionId?.takeIf { known -> _captions.value.any { it.id == known } } ?: job.id
            musicCaptionId = id
            with(stabilizer) {
                onSourceText(id, musicLabel, clock())
                _captions.value = onTranslated(id, musicLabel, clock())
            }
        }
        recordLag(job, 0)
    }

    private fun recordLag(job: Job, translateMs: Long) {
        val lag = clock() - job.queuedAtMs
        bump { it.copy(lastTranslateMs = translateMs, lastLagMs = lag, maxLagMs = maxOf(it.maxLagMs, lag)) }
    }

    private companion object {
        /** Compares primary language subtags case-insensitively: "en", "EN_us" and "en-GB" are the same. */
        fun sameLanguage(a: String, b: String): Boolean = primary(a) == primary(b)

        fun primary(tag: String): String = tag.trim().split('-', '_').first().lowercase()
    }
}
