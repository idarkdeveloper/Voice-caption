package com.voicecaption.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voicecaption.R
import com.voicecaption.appContainer
import com.voicecaption.ui.state.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps "Download all languages" alive after the app is closed. It owns no download logic: the
 * app-scoped language-pack controller does the work, and this service only holds a foreground
 * notification (with progress and Cancel) while that controller is downloading, then stops itself
 * and posts a short result notification.
 */
class LanguagePackService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = appContainer.languagePackController
        // Every startForegroundService() must be answered by startForeground(), even when we stop right after.
        showForeground(getString(R.string.langpack_title), 0f)
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            finish()
            return START_NOT_STICKY
        }
        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
            s.launch {
                controller.state.collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> showForeground(state.label, state.progress)
                        DownloadState.Done -> { notifyResult(getString(R.string.langpack_done)); finish() }
                        is DownloadState.Failed -> { notifyResult(getString(R.string.langpack_failed, state.message)); finish() }
                        DownloadState.Idle -> finish()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.langpack_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun showForeground(text: String, progress: Float) {
        ensureChannel()
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, LanguagePackService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.langpack_title))
            .setContentText(text)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.langpack_cancel), cancel).build())
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyResult(text: String) {
        ensureChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.langpack_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val ACTION_CANCEL = "com.voicecaption.action.CANCEL_LANGUAGE_PACKS"
        private const val CHANNEL_ID = "language_packs"
        private const val NOTIFICATION_ID = 2
        private const val RESULT_NOTIFICATION_ID = 3

        /** Starts the foreground notification for a download that is already running. Call from the foreground app. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LanguagePackService::class.java))
        }
    }
}
