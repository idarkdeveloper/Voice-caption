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
import com.voicecaption.asr.HttpUrlConnectionDownloader
import com.voicecaption.asr.ModelManager
import com.voicecaption.asr.ModelSelector
import com.voicecaption.asr.WhisperLocal
import com.voicecaption.audio.AudioCapture
import com.voicecaption.audio.CaptureBlockedDetector
import com.voicecaption.caption.CaptionStabilizer
import com.voicecaption.core.ModelSize
import com.voicecaption.overlay.OverlayRenderer
import com.voicecaption.pipeline.CaptionPipeline
import com.voicecaption.pipeline.PipelineError
import com.voicecaption.translate.MlKitTranslator
import com.voicecaption.vad.SileroVad
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
    private var whisper: WhisperLocal? = null
    private var translator: MlKitTranslator? = null
    private var translatorMessageShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> teardown()
            ACTION_START -> if (!started && !tornDown) {
                started = true
                // Must be foreground before getMediaProjection (Android 14).
                startForeground()
                begin(intent)
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
            val modelSize = pickInstalledModel(settings.modelSize)
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
            val whisperInstance = WhisperLocal(File(ServiceDependencies.modelsDir(this), modelSize.dirName()), modelSize)
                .also { whisper = it }
            val translatorInstance = MlKitTranslator(this).also { translator = it }
            val pipe = CaptionPipeline(
                segmenter = VadSegmenter(vadInstance),
                recognizer = whisperInstance,
                translator = translatorInstance,
                stabilizer = CaptionStabilizer(),
                settings = { ServiceDependencies.settings() },
                scope = scope,
                clock = SystemClock::elapsedRealtime,
            ).also { pipeline = it }
            val audio = AudioCapture(proj).also { capture = it }

            mon.dispatch(ServiceEvent.Ready)
            launchCollectors(pipe, audio, mon, renderer)
            scope.launch {
                if (!translatorInstance.isReady(settings.sourceLang, settings.targetLang)) onMain { showTranslatorMissing() }
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
            pipe.errors.collect { if (it is PipelineError.TranslatorNotReady) showTranslatorMissing() }
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
                onMain { teardown() } // capture ended without error (stop requested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMain { fail(R.string.capture_error_unexpected) }
            }
        }
    }

    /** Main thread: reacts to a state the monitor reached while processing frames. */
    private fun onState(state: ServiceState) {
        when (state) {
            ServiceState.BLOCKED -> overlay?.showMessage(getString(R.string.capture_blocked))
            ServiceState.RUNNING -> pipeline?.let { overlay?.render(it.captions.value, ServiceDependencies.settings()) }
            ServiceState.STOPPED -> teardown()
            else -> Unit
        }
    }

    /** Main thread: an externally triggered event (projection stopped). */
    private fun onEvent(event: ServiceEvent) {
        if (monitor?.dispatch(event) == ServiceState.STOPPED) teardown()
    }

    private fun showTranslatorMissing() {
        if (translatorMessageShown || tornDown) return
        translatorMessageShown = true
        overlay?.showMessage(getString(R.string.capture_translation_missing))
    }

    /**
     * Main thread: reports [messageRes] (on the overlay when allowed, else as a toast), stops the
     * capture right away and tears everything down after [MESSAGE_LINGER_MS] so it can be read.
     */
    private fun fail(messageRes: Int) {
        if (tornDown) return
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
        scope.launch(Dispatchers.Main) {
            delay(MESSAGE_LINGER_MS)
            teardown()
        }
    }

    /** Main thread. Idempotent: releases everything in dependency order, then stops the service. */
    private fun teardown() {
        if (tornDown) return
        tornDown = true
        monitor?.dispatch(ServiceEvent.UserStop)
        capture?.stop()
        // Flush first, then close the models: pipeline.stop() waits out any in-flight onFrame.
        pipeline?.stop()
        scope.cancel()
        projection?.let { p ->
            projectionCallback?.let { runCatching { p.unregisterCallback(it) } }
            runCatching { p.stop() }
        }
        runCatching { whisper?.close() }
        runCatching { vad?.close() }
        runCatching { translator?.close() }
        overlay?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The size to run: the selector's choice if installed, else the preferred one, else null. */
    private fun pickInstalledModel(preferred: ModelSize): ModelSize? {
        val power = getSystemService(PowerManager::class.java)
        val battery = getSystemService(BatteryManager::class.java)
        val chosen = ModelSelector.choose(
            preferred,
            power.currentThermalStatus,
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
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
        private const val ACTION_START = "com.voicecaption.action.START_CAPTURE"
        private const val ACTION_STOP = "com.voicecaption.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val MESSAGE_LINGER_MS = 4000L

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
