package com.voicecaption.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voicecaption.R
import com.voicecaption.asr.AutoRecognizer
import com.voicecaption.asr.HttpUrlConnectionDownloader
import com.voicecaption.asr.ModelManager
import com.voicecaption.asr.ModelSelector
import com.voicecaption.asr.SenseVoiceLocal
import com.voicecaption.asr.SpeechRecognizer
import com.voicecaption.asr.WhisperLocal
import com.voicecaption.audio.AudioCapture
import com.voicecaption.audio.CaptureBlockedDetector
import com.voicecaption.caption.CaptionStabilizer
import com.voicecaption.core.ModelSize
import com.voicecaption.overlay.OverlayRenderer
import com.voicecaption.pipeline.CaptionPipeline
import com.voicecaption.pipeline.PipelineError
import com.voicecaption.translate.MlKitTranslator
import com.voicecaption.settings.SupportedLanguages
import com.voicecaption.vad.SileroVad
import com.voicecaption.vad.VadConfig
import com.voicecaption.vad.VadSegmenter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that captions the audio other apps play.
 *
 * Lifecycle: [start] -> `onStartCommand(ACTION_START)` promotes the service to the foreground
 * (type mediaProjection) FIRST, then obtains the MediaProjection (Android 14 order), registers its
 * stop callback, checks [StartupCheck], builds VAD / Whisper / translator / pipeline and starts
 * the capture. It ends through [teardown] from: the Stop notification action, [stop], the
 * projection being stopped (system "stop sharing"), the overlay permission being revoked, an
 * unexpected error, or `onDestroy`. [teardown] is idempotent.
 *
 * Threading: the service owns one `SupervisorJob` scope on [Dispatchers.Default] (pipeline worker,
 * ticker, capture loop). EVERY overlay call (show, render, showMessage, hide) happens on the main
 * thread: either inline in service/projection callbacks (already main) or in a coroutine
 * launched on [Dispatchers.Main], which are FIFO with each other. The audio thread only touches
 * [CaptureMonitor] (thread-safe), [CaptionPipeline.onFrame] (thread-safe) and posts to main.
 *
 * Model size is chosen once at start by [ModelSelector] from the thermal status and battery level
 * and is not re-evaluated while running.
 *
 * Decisions live in the pure [CaptureMonitor] and [StartupCheck]; this class is wiring.
 */
class CaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorLock = Any()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, _ -> onMain { fail(R.string.capture_error_unexpected) } },
    )

    // Main-thread state.
    private var started = false
    private var tornDown = false
    private var overlay: OverlayRenderer? = null
    private var monitor: CaptureMonitor? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capture: AudioCapture? = null
    private var pipeline: CaptionPipeline? = null
    private var vad: SileroVad? = null
    private var whisper: SpeechRecognizer? = null
    private var translator: MlKitTranslator? = null
    private var oneTimeMessageShown = false
    private val recognizerFailures = RecognizerFailureTracker()
    private var failing = false
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_STOP -> teardown()
            ACTION_START -> {
                // Every startForegroundService() must be answered by startForeground(), even when
                // this start is ignored below; and it must precede getMediaProjection (Android 14).
                startForeground()
                if (tornDown) {
                    // This instance is already shutting down. The new start (and its one-shot
                    // projection consent) is dropped; stop this instance so Android destroys it,
                    // and the user can start again, which then gets a fresh instance.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else if (!started) {
                    started = true
                    begin(intent)
                } // else: a run (or its error linger) is in progress; the extra start is ignored.
            }
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startForeground() {
        val channelId = CHANNEL_ID
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.capture_action_stop), stopIntent).build(),
            )
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    /** Runs on the main thread. Any failure here ends the run with a message, never a crash. */
    private fun begin(intent: Intent) {
        try {
            val settings = ServiceDependencies.settings()
            val renderer = OverlayRenderer(this).also { overlay = it }
            val modelSize = pickInstalledModel(settings.activeModel)
            val check = StartupCheck.evaluate(
                overlayAllowed = renderer.canDrawOverlays(),
                recordAudioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                modelInstalled = modelSize != null,
                sourceLang = settings.sourceLang,
            )
            if (check is StartupResult.Failed) {
                fail(messageFor(check.problem))
                return
            }
            val resultData = intent.getParcelableExtraCompat()
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            if (resultData == null) {
                fail(R.string.capture_error_start_failed)
                return
            }
            checkNotNull(modelSize)

            renderer.show()
            val mon = CaptureMonitor(
                CaptureBlockedDetector(isMediaPlaying = { getSystemService(AudioManager::class.java).isMusicActive }),
                overlayAllowed = renderer::canDrawOverlays,
            ).also { monitor = it }
            mon.dispatch(ServiceEvent.Start)

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, resultData) ?: run {
                fail(R.string.capture_error_start_failed)
                return
            }
            projection = proj
            // Android 14: a callback must be registered before capture starts.
            val callback = object : MediaProjection.Callback() {
                override fun onStop() = onMain { onEvent(ServiceEvent.ProjectionStopped) }
            }
            projectionCallback = callback
            proj.registerCallback(callback, mainHandler)

            val vadInstance = SileroVad(this).also { vad = it }
            val translatorInstance = MlKitTranslator(this).also { translator = it }
            val sourceAuto = SupportedLanguages.isAuto(settings.sourceLang)
            // English captions for a non-English film: Whisper translates the speech directly, which
            // measured clearly more accurate than transcribing and then translating with ML Kit.
            // With auto-detect the film may be in any language, so Whisper translates whenever the
            // captions are English (for English speech that is a no-op).
            val whisperTranslates = modelSize != ModelSize.SENSEVOICE &&
                settings.targetLang.lowercase().startsWith("en") &&
                (sourceAuto || !settings.sourceLang.lowercase().startsWith("en"))
            val modelsDir = ServiceDependencies.modelsDir(this)
            val modelDir = File(modelsDir, modelSize.dirName())
            val whisperInstance: SpeechRecognizer = when {
                modelSize == ModelSize.SENSEVOICE -> SenseVoiceLocal(modelDir)
                sourceAuto -> AutoRecognizer(
                    whisper = WhisperLocal(modelDir, modelSize, whisperTranslates),
                    // SenseVoice is used for Chinese, Japanese, Korean and Cantonese when it is installed.
                    senseVoice = if (ModelManager(modelsDir, HttpUrlConnectionDownloader()).isInstalled(ModelSize.SENSEVOICE)) {
                        SenseVoiceLocal(File(modelsDir, ModelSize.SENSEVOICE.dirName()))
                    } else {
                        null
                    },
                    translatorReady = { source, target -> translatorInstance.isReady(source, target) },
                    targetLang = { ServiceDependencies.settings().targetLang },
                )
                else -> WhisperLocal(modelDir, modelSize, whisperTranslates)
            }
            whisper = whisperInstance
            val pipe = CaptionPipeline(
                segmenter = VadSegmenter(vadInstance, VadConfig(partialEveryMs = if (modelSize == ModelSize.SENSEVOICE) PARTIAL_FAST_MS else PARTIAL_EVERY_MS)),
                recognizer = whisperInstance,
                translator = translatorInstance,
                stabilizer = CaptionStabilizer(maxLines = 1) // a new sentence replaces the old one,
                settings = { ServiceDependencies.settings() },
                scope = scope,
                clock = SystemClock::elapsedRealtime,
                musicLabel = getString(R.string.caption_music),
            ).also { pipeline = it }
            val audio = AudioCapture(proj).also { capture = it }

            mon.dispatch(ServiceEvent.Ready)
            launchCollectors(pipe, audio, mon, renderer)
            CaptureStatus.set(true)
            scope.launch {
                if (!sourceAuto && !whisperTranslates && !translatorInstance.isReady(settings.sourceLang, settings.targetLang)) onMain { showOneTimeMessage(R.string.capture_translation_missing) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(R.string.capture_error_start_failed)
        }
    }

    private fun launchCollectors(
        pipe: CaptionPipeline,
        audio: AudioCapture,
        mon: CaptureMonitor,
        renderer: OverlayRenderer,
    ) {
        // Captions and errors: collected on the main thread, the only thread that touches the overlay.
        scope.launch(Dispatchers.Main) {
            pipe.captions.collect { renderer.render(it, ServiceDependencies.settings()) }
        }
        scope.launch(Dispatchers.Main) {
            pipe.errors.collect { onPipelineError(it) }
        }
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val modelLabel = when (val r = whisper) {
                is SenseVoiceLocal -> "SENSEVOICE"
                is AutoRecognizer -> "AUTO"
                is WhisperLocal -> r.size.name + if (r.outputLanguage != null) "+translate" else ""
                else -> "?"
            }
            scope.launch(Dispatchers.Main) {
                pipe.stats.collect { st ->
                    val raw = if (st.lastRaw.isEmpty()) "(empty)" else if (st.lastNoise) "(filtered) ${st.lastRaw}" else st.lastRaw
                    renderer.setDebugLine(
                        "${modelLabel}${(whisper as? AutoRecognizer)?.let { " ${it.engine}" }.orEmpty()} lang ${st.lastLang.ifEmpty { "?" }} " +
                            "cut ${st.cut} drop ${st.dropped} | seg ${st.lastSegmentMs}ms " +
                            "asr ${st.lastDecodeMs}ms lag ${st.lastLagMs}ms\nraw: $raw",
                    )
                }
            }
        }
        // Capture loop on the audio thread (flowOn(IO) inside AudioCapture).
        scope.launch {
            var last = mon.state
            try {
                audio.frames().collect { frame ->
                    val state = mon.onFrame(frame)
                    if (state != last) {
                        last = state
                        onMain { onState(state) }
                    }
                    if (state == ServiceState.STOPPED) audio.stop() else pipe.onFrame(frame)
                }
                onMain { if (!failing) teardown() } // capture ended without error (stop requested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMain { onCaptureFailed() }
            }
        }
    }

    /**
     * Main thread: reacts to a pipeline error. Repeated recognizer failures end the run (otherwise
     * nothing would ever appear while the UI says "listening"); a translator failure keeps the
     * source captions, so it only shows a one-time message.
     */
    private fun onPipelineError(error: PipelineError) {
        if (recognizerFailures.onError(error)) {
            fail(R.string.capture_error_recognizer)
            return
        }
        when (error) {
            PipelineError.TranslatorNotReady -> showOneTimeMessage(R.string.capture_translation_missing)
            is PipelineError.Translator -> showOneTimeMessage(R.string.capture_translation_failed)
            is PipelineError.Recognizer -> Unit
        }
    }

    /**
     * Main thread: the capture loop failed. A read error can race with the system's "stop sharing"
     * callback, so a failure is only reported if, after a short grace period, the projection has
     * not stopped the run meanwhile ([captureFailureAction]). Teardown stays exactly-once.
     */
    private fun onCaptureFailed() {
        if (failing) return
        when (captureFailureAction(currentState(), tornDown)) {
            CaptureFailureAction.TEARDOWN -> teardown()
            CaptureFailureAction.IGNORE -> Unit
            CaptureFailureAction.FAIL -> scope.launch(Dispatchers.Main) {
                delay(PROJECTION_GRACE_MS)
                if (failing) return@launch
                when (captureFailureAction(currentState(), tornDown)) {
                    CaptureFailureAction.TEARDOWN -> teardown()
                    CaptureFailureAction.IGNORE -> Unit
                    CaptureFailureAction.FAIL -> fail(R.string.capture_error_unexpected)
                }
            }
        }
    }

    private fun currentState(): ServiceState = monitor?.state ?: ServiceState.STOPPED

    /** Main thread: reacts to a state the monitor reached while processing frames. */
    private fun onState(state: ServiceState) {
        when (state) {
            ServiceState.BLOCKED -> {
                // Playback capture only hears what the phone plays: a muted media volume looks the
                // same as an app that blocks capture, so say which one is likely.
                val am = getSystemService(AudioManager::class.java)
                val muted = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)
                overlay?.showMessage(getString(if (muted) R.string.capture_volume_off else R.string.capture_blocked))
            }
            ServiceState.RUNNING -> pipeline?.let { overlay?.render(it.captions.value, ServiceDependencies.settings()) }
            ServiceState.STOPPED -> if (!failing) teardown() // fail() owns the delayed teardown
            else -> Unit
        }
    }

    /** Main thread: an externally triggered event (projection stopped). */
    private fun onEvent(event: ServiceEvent) {
        if (monitor?.dispatch(event) == ServiceState.STOPPED && !failing) teardown()
    }

    /** Shows [messageRes] on the overlay the first time only; later calls (any message) are ignored. */
    private fun showOneTimeMessage(messageRes: Int) {
        if (oneTimeMessageShown || tornDown) return
        oneTimeMessageShown = true
        overlay?.showMessage(getString(messageRes))
    }

    /**
     * Main thread: reports [messageRes] (on the overlay when allowed, else as a toast), stops the
     * capture right away and tears everything down after [MESSAGE_LINGER_MS] so it can be read.
     * Guarantee: the delayed teardown is scheduled first, so it happens (once) even if showing the
     * message fails.
     */
    private fun fail(messageRes: Int) {
        if (tornDown || failing) return
        failing = true
        scope.launch(Dispatchers.Main) {
            delay(MESSAGE_LINGER_MS)
            teardown()
        }
        runCatching {
            val text = getString(messageRes)
            val renderer = overlay
            monitor?.dispatch(ServiceEvent.UserStop)
            capture?.stop()
            if (renderer != null && renderer.canDrawOverlays()) {
                renderer.show()
                renderer.showMessage(text)
            } else {
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Main thread. Idempotent: releases everything in dependency order, then stops the service.
     *
     * The native closes (Whisper, VAD, translator) run on a dedicated thread, never on main:
     * `WhisperLocal.close()` takes the lock a running native decode holds, and a decode cannot be
     * interrupted, so closing inline could freeze the UI for a whole segment (up to ~30 s) or
     * trigger an ANR. The thread is started after [CaptionPipeline.stop] and captures only the
     * three objects it closes (not the service), so it finishes after the service is gone; none of
     * the closes touch the main thread or the overlay. Main-thread work (overlay, foreground,
     * stopSelf) stays inline and always runs, even if an earlier step throws.
     */
    private fun teardown() {
        if (tornDown) return
        tornDown = true
        CaptureStatus.set(false)
        try {
            runCatching { monitor?.dispatch(ServiceEvent.UserStop) }
            runCatching { capture?.stop() }
            // Flush first; the native closes below only start after this has returned or thrown.
            runCatching { pipeline?.stop() }
            runCatching { scope.cancel() }
            projection?.let { p ->
                projectionCallback?.let { runCatching { p.unregisterCallback(it) } }
                runCatching { p.stop() }
            }
            runCatching { overlay?.hide() }
        } finally {
            closeNativeOffMain(whisper, vad, translator)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } finally {
                // Only stops the service if no newer start arrived since (that one is handled above).
                stopSelfResult(latestStartId)
            }
        }
    }

    /** The size to run: the selector's choice if installed, else the preferred one, else null. */
    private fun pickInstalledModel(preferred: ModelSize): ModelSize? {
        val power = getSystemService(PowerManager::class.java)
        val battery = getSystemService(BatteryManager::class.java)
        val chosen = ModelSelector.choose(
            preferred,
            power.currentThermalStatus,
            normalizeBatteryPercent(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)),
        )
        val models = ModelManager(ServiceDependencies.modelsDir(this), HttpUrlConnectionDownloader())
        return listOf(chosen, preferred).firstOrNull { models.isInstalled(it) }
    }

    private fun messageFor(problem: StartupProblem): Int = when (problem) {
        StartupProblem.OVERLAY_PERMISSION -> R.string.capture_error_overlay_permission
        StartupProblem.RECORD_AUDIO -> R.string.capture_error_record_audio
        StartupProblem.SOURCE_LANGUAGE -> R.string.capture_error_source_language
        StartupProblem.MODEL_MISSING -> R.string.capture_error_model_missing
    }

    private fun ModelSize.dirName() = name.lowercase()

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    @Suppress("DEPRECATION") // typed getParcelableExtra needs API 33; minSdk is 29
    private fun Intent.getParcelableExtraCompat(): Intent? = getParcelableExtra(EXTRA_DATA)

    companion object {
        /** While someone is speaking, show a provisional caption refreshed this often (Whisper). */
        private const val PARTIAL_EVERY_MS = 1500

        /** SenseVoice decodes in about 100 ms, so it can refresh the provisional caption faster. */
        private const val PARTIAL_FAST_MS = 800

        /** Closes the native resources in order, each independently, on a non-daemon background thread. */
        private fun closeNativeOffMain(whisper: SpeechRecognizer?, vad: SileroVad?, translator: MlKitTranslator?) {
            Thread({
                runCatching { whisper?.close() }
                runCatching { vad?.close() }
                runCatching { translator?.close() }
            }, "capture-native-close").start()
        }

        private const val ACTION_START = "com.voicecaption.action.START_CAPTURE"
        private const val ACTION_STOP = "com.voicecaption.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val MESSAGE_LINGER_MS = 4000L
        private const val PROJECTION_GRACE_MS = 300L

        /** Starts captioning with the MediaProjection consent result ([resultCode], [data]). */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops captioning; the service tears down in `onDestroy`. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
