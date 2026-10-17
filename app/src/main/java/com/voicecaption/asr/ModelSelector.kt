package com.voicecaption.asr

import com.voicecaption.core.ModelSize

/** Picks the Whisper model to run given device health. Pure function, no Android types. */
object ModelSelector {
    /** `PowerManager.THERMAL_STATUS_MODERATE`. */
    private const val THERMAL_MODERATE = 2
    private const val LOW_BATTERY_PCT = 15

    /**
     * Returns [ModelSize.BASE] when the device is at [THERMAL_MODERATE] thermal status or hotter,
     * or the battery is below 15 %; otherwise the user's [preferred] size.
     */
    fun choose(preferred: ModelSize, thermalStatus: Int, batteryPct: Int): ModelSize =
        when {
            preferred == ModelSize.SENSEVOICE -> preferred // already lighter than Whisper small; never swapped for another engine
            thermalStatus >= THERMAL_MODERATE || batteryPct < LOW_BATTERY_PCT -> ModelSize.BASE
            else -> preferred
        }
}
