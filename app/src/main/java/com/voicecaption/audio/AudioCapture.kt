package com.voicecaption.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.voicecaption.core.PcmFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Captures what other apps play (USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN) through [projection] as
 * 16 kHz mono 16-bit PCM, cut into 512-sample [PcmFrame]s.
 *
 * [frames] is cold: each collection creates one [AudioRecord] and runs a blocking read loop on
 * [Dispatchers.IO]; the record is stopped and released when the collector is cancelled, [stop] is
 * called, or the loop fails. Timestamps are milliseconds since the capture started, in 32 ms
 * steps (see [FrameChunker]). It fails with [IllegalStateException] if RECORD_AUDIO is not
 * granted, the record cannot be initialized, a read fails, or
 * [ZeroReadCounter.DEFAULT_LIMIT] reads in a row return no data.
 *
 * The caller must have registered a [MediaProjection.Callback] on [projection] before collecting
 * (required on Android 14+), and must hold RECORD_AUDIO (checked by the service up front; a
 * missing grant still surfaces here as a clear exception rather than a crash).
 */
class AudioCapture(private val projection: MediaProjection) {
    @Volatile
    private var stopped = false

    /** Frames of captured playback audio; completes normally after [stop]. */
    fun frames(): Flow<PcmFrame> = flow {
        val record = createRecord()
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio capture did not start" }
            val chunker = FrameChunker(FRAME_SAMPLES, SAMPLE_RATE)
            val buffer = ShortArray(FRAME_SAMPLES)
            val zeroReads = ZeroReadCounter()
            while (!stopped && currentCoroutineContext().isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (stopped) break
                check(n >= 0) { "Audio capture read failed (code $n)" }
                if (n == 0) {
                    // A dead record can return 0 forever; give up instead of spinning on the CPU.
                    check(!zeroReads.record(n)) { "Audio capture returned no data" }
                    delay(ZERO_READ_PAUSE_MS)
                    continue
                }
                zeroReads.record(n)
                for (frame in chunker.push(buffer, n)) emit(frame)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    /** Ends the read loop of the running [frames] collection. Idempotent; safe from any thread. */
    fun stop() {
        stopped = true
    }

    private fun createRecord(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * BYTES_PER_SAMPLE * BUFFER_FRAMES)
        val record = try {
            // Lint cannot see the service's RECORD_AUDIO check; a missing grant is a SecurityException.
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: SecurityException) {
            throw IllegalStateException("RECORD_AUDIO permission is required for audio capture", e)
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("Audio capture is not supported on this device", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Audio capture could not be initialized")
        }
        return record
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 512
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_FRAMES = 8
        const val ZERO_READ_PAUSE_MS = 5L
    }
}
