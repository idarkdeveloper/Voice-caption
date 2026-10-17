package com.voicecaption.service

import android.content.Context
import com.voicecaption.core.CaptionSettings
import java.io.File

/**
 * Hooks the foreground service reads at run time, so the UI layer can plug in its own state
 * without the service depending on it.
 *
 * Task 11 replaces [settings] with the DataStore-backed repository. The service calls it once at
 * start (language, model size) and the pipeline and overlay call it again whenever they need the
 * latest values, so it must be cheap and thread-safe (return an in-memory snapshot).
 */
object ServiceDependencies {
    /** Current caption settings. Defaults to [CaptionSettings] defaults until the UI provides them. */
    @Volatile
    var settings: () -> CaptionSettings = { CaptionSettings() }

    /** Saves the dragged caption-box positions (dp from the bottom edge; null = default). Wired by the app. */
    @Volatile
    var savePositions: (translationDp: Int?, originalDp: Int?) -> Unit = { _, _ -> }

    /** Directory holding the installed Whisper models (`<dir>/<size>/`); same place the app downloads them to. */
    @Volatile
    var modelsDir: (Context) -> File = { File(it.filesDir, "models") }
}
