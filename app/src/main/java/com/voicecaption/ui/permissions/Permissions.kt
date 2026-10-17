package com.voicecaption.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voicecaption.ui.state.PermissionState

/** Android permission and consent helpers; the pure rules live in `ReadinessChecker`. */
object Permissions {
    /** True when the app may draw over other apps. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Opens this app's "Display over other apps" settings page. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** True when notifications are allowed; implicitly true below API 33 (no runtime permission). */
    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** True when the microphone (RECORD_AUDIO) permission is granted; audio capture needs it. */
    fun recordAudioGranted(context: Context): Boolean = granted(context, Manifest.permission.RECORD_AUDIO)

    /** The system screen-capture consent dialog intent; start it for a result every session. */
    fun projectionIntent(context: Context): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    /** All three flags now, for `MainViewModel.refreshPermissions` (call from `onResume`). */
    fun current(context: Context): PermissionState = PermissionState(
        overlay = canDrawOverlays(context),
        notifications = notificationsGranted(context),
        recordAudio = recordAudioGranted(context),
    )

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
