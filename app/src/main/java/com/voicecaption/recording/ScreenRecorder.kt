package com.voicecaption.recording

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface

/**
 * Records the screen, including the caption boxes drawn over it, and the sound the phone plays into
 * an MP4 in Movies/TransCap. It uses the SAME [MediaProjection] as the caption service, which is why
 * it can run while captions work (Android allows only one projection at a time, so a separate screen
 * recorder would end the captions).
 *
 * Video: a virtual display mirrors the screen into an H.264 encoder's input surface. Audio: a second
 * playback-capture [AudioRecord] (44.1 kHz stereo) feeds an AAC encoder. Both go to one [MediaMuxer].
 * The size is fixed when recording starts; turning the phone later does not change it.
 *
 * Call [start] once and [stop] once; [stop] blocks for a short time and must not run on the main thread.
 */
class ScreenRecorder(private val context: Context) {
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile
    private var running = false

    // Muxer state, guarded by [muxLock].
    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var baseUs = -1L
    private var lastVideoUs = 0L
    private var lastAudioUs = 0L
    private var samplesWritten = 0L

    /** Starts recording; on failure everything is cleaned up and the failure is returned. */
    fun start(projection: MediaProjection, screenWidth: Int, screenHeight: Int, dpi: Int): Result<Unit> = try {
        val (w, h) = RecordingMath.videoSize(screenWidth, screenHeight)
        openOutput()
        startVideo(projection, w, h, dpi)
        startAudio(projection)
        running = true
        videoThread = Thread({ drainLoop(isVideo = true) }, "TransCapRecVideo").also { it.start() }
        audioThread = Thread(::audioLoop, "TransCapRecAudio").also { it.start() }
        Result.success(Unit)
    } catch (e: Exception) {
        Log.w(TAG, "Screen recording could not start", e)
        running = false
        cleanup(deleteOutput = true)
        Result.failure(e)
    }

    /** Finishes the file and returns its Uri, or null if nothing was recorded. Blocks briefly. */
    fun stop(): Uri? {
        if (!running && uri == null) return null
        running = false
        runCatching { videoEncoder?.signalEndOfInputStream() }
        audioThread?.join(JOIN_MS)
        videoThread?.join(JOIN_MS)
        val result = uri
        val recorded = synchronized(muxLock) { muxerStarted && samplesWritten > 0 }
        cleanup(deleteOutput = !recorded)
        return if (recorded) result else null
    }

    private fun openOutput() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, RecordingMath.fileName(System.currentTimeMillis()))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TransCap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val created = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create the video file"
        }
        uri = created
        val descriptor = checkNotNull(resolver.openFileDescriptor(created, "w")) { "Could not open the video file" }
        pfd = descriptor
        muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun startVideo(projection: MediaProjection, w: Int, h: Int, dpi: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, RecordingMath.videoBitrate(w, h))
            setInteger(MediaFormat.KEY_FRAME_RATE, RecordingMath.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen produces no new frames; repeat the last one so the video keeps a steady clock.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        inputSurface = surface
        encoder.start()
        // Android 14: one virtual display per projection; the service already registered its stop callback.
        virtualDisplay = projection.createVirtualDisplay(
            "TransCap recording",
            w,
            h,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
    }

    private fun startAudio(projection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            // The caption service already checked RECORD_AUDIO; lint cannot see that.
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_CHUNK * 4))
                .build()
        } catch (e: SecurityException) {
            throw IllegalStateException("RECORD_AUDIO permission is required to record the sound", e)
        }
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Recording audio could not be initialized" }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK * 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    /** Video: blocks on the encoder's output until it signals the end of the stream. */
    private fun drainLoop(isVideo: Boolean) {
        val encoder = (if (isVideo) videoEncoder else audioEncoder) ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (drainOnce(encoder, info, isVideo, timeoutUs = 10_000L)) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video drain failed", e)
        }
    }

    /** Returns true when the end-of-stream buffer was seen. */
    private fun drainOnce(encoder: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean, timeoutUs: Long): Boolean {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(isVideo, encoder.outputFormat)
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buffer != null && info.size > 0) writeSample(isVideo, buffer, info)
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    /** Audio: reads PCM, feeds the AAC encoder with timestamps on the monotonic clock, and drains it. */
    private fun audioLoop() {
        val record = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(AUDIO_CHUNK)
        var startUs = -1L
        var frames = 0L
        try {
            record.startRecording()
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) {
                    Thread.sleep(10)
                    continue
                }
                if (startUs < 0) startUs = System.nanoTime() / 1000 - n / BYTES_PER_FRAME * 1_000_000L / AUDIO_RATE
                var offset = 0
                while (offset < n) {
                    val index = encoder.dequeueInputBuffer(10_000L)
                    if (index < 0) {
                        drainOnce(encoder, info, isVideo = false, timeoutUs = 0)
                        continue
                    }
                    val input = encoder.getInputBuffer(index) ?: continue
                    val size = minOf(input.capacity(), n - offset)
                    input.clear()
                    input.put(pcm, offset, size)
                    encoder.queueInputBuffer(index, 0, size, startUs + frames * 1_000_000L / AUDIO_RATE, 0)
                    frames += size / BYTES_PER_FRAME
                    offset += size
                }
                drainOnce(encoder, info, isVideo = false, timeoutUs = 0)
            }
            // End of stream.
            var queued = false
            while (!queued) {
                val index = encoder.dequeueInputBuffer(10_000L)
                if (index >= 0) {
                    encoder.queueInputBuffer(index, 0, 0, startUs.coerceAtLeast(0) + frames * 1_000_000L / AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drainOnce(encoder, info, isVideo = false, timeoutUs = 0)
                }
            }
            while (!drainOnce(encoder, info, isVideo = false, timeoutUs = 20_000L)) { /* wait for the end */ }
        } catch (e: Exception) {
            Log.w(TAG, "Audio recording failed", e)
        } finally {
            runCatching { record.stop() }
        }
    }

    private fun addTrack(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxLock) {
            val m = muxer ?: return
            if (muxerStarted) return // the video-only fallback already started the file
            val index = m.addTrack(format)
            if (isVideo) videoTrack = index else audioTrack = index
            if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                m.start()
                muxerStarted = true
                muxLock.notifyAll()
            }
        }
    }

    private fun writeSample(isVideo: Boolean, buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            var waited = 0
            while (!muxerStarted && waited < MUX_WAIT_MS) {
                muxLock.wait(50)
                waited += 50
            }
            if (!muxerStarted && isVideo && videoTrack >= 0) {
                // No audio arrived in time (nothing playing it, or capture refused): keep the video without sound.
                muxer?.start()
                muxerStarted = true
            }
            if (!muxerStarted) return
            val track = if (isVideo) videoTrack else audioTrack
            if (track < 0) return
            if (baseUs < 0) baseUs = info.presentationTimeUs
            var pts = (info.presentationTimeUs - baseUs).coerceAtLeast(0)
            // The muxer wants non-decreasing times per track.
            if (isVideo) {
                pts = maxOf(pts, lastVideoUs)
                lastVideoUs = pts
            } else {
                pts = maxOf(pts, lastAudioUs)
                lastAudioUs = pts
            }
            info.presentationTimeUs = pts
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer?.writeSampleData(track, buffer, info)
            samplesWritten++
        }
    }

    private fun cleanup(deleteOutput: Boolean) {
        runCatching { virtualDisplay?.release() }
        runCatching { inputSurface?.release() }
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        runCatching { audioRecord?.release() }
        val started = synchronized(muxLock) { muxerStarted }
        runCatching { if (started) muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { pfd?.close() }
        val target = uri
        if (target != null) {
            runCatching {
                if (deleteOutput) {
                    context.contentResolver.delete(target, null, null)
                } else {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(target, done, null, null)
                }
            }
        }
        virtualDisplay = null
        inputSurface = null
        videoEncoder = null
        audioEncoder = null
        audioRecord = null
        muxer = null
        pfd = null
        if (deleteOutput) uri = null
    }

    private companion object {
        const val TAG = "ScreenRecorder"
        const val AUDIO_RATE = 44_100
        const val BYTES_PER_FRAME = 4 // 16-bit stereo
        const val AUDIO_CHUNK = 8192
        const val JOIN_MS = 4_000L
        const val MUX_WAIT_MS = 3_000
    }
}
