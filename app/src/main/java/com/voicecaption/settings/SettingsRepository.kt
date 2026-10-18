package com.voicecaption.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.ColorMath
import com.voicecaption.core.ModelSize
import com.voicecaption.overlay.OverlayMath
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Preference keys; [ModelSize] is stored by name so reordering the enum cannot corrupt it. */
internal object SettingsKeys {
    val sourceLang = stringPreferencesKey("source_lang")
    val targetLang = stringPreferencesKey("target_lang")
    val fontSizeSp = intPreferencesKey("font_size_sp")
    val opacity = floatPreferencesKey("opacity")
    val bilingual = booleanPreferencesKey("bilingual")
    val modelSize = stringPreferencesKey("model_size")
    val autoModel = booleanPreferencesKey("auto_model")
    val showDiagnostics = booleanPreferencesKey("show_diagnostics")
    val recordScreen = booleanPreferencesKey("record_screen")
    val translationOffsetDp = intPreferencesKey("translation_offset_dp")
    val originalOffsetDp = intPreferencesKey("original_offset_dp")
    val textColor = intPreferencesKey("text_color")
    val plateColor = intPreferencesKey("plate_color")
}

internal val FONT_SIZE_RANGE_SP = 14..32

/**
 * Clamps [raw] into legal values: font 14..32 sp, opacity 0.5..1.0 (the overlay's readability
 * floor), languages coerced to a supported code (fallback "en"; never "auto").
 */
private const val MAX_OFFSET_DP = 3000

internal fun validate(raw: CaptionSettings): CaptionSettings {
    val defaults = CaptionSettings()
    return raw.copy(
        sourceLang = SupportedLanguages.coerceSource(raw.sourceLang, "en"),
        targetLang = SupportedLanguages.coerce(raw.targetLang, "en"),
        fontSizeSp = raw.fontSizeSp.coerceIn(FONT_SIZE_RANGE_SP),
        opacity = if (raw.opacity.isNaN()) defaults.opacity else raw.opacity.coerceIn(OverlayMath.MIN_OPACITY, 1f),
        textColor = ColorMath.opaque(raw.textColor),
        plateColor = ColorMath.opaque(raw.plateColor),
        translationOffsetDp = raw.translationOffsetDp?.coerceIn(0, MAX_OFFSET_DP),
        originalOffsetDp = raw.originalOffsetDp?.coerceIn(0, MAX_OFFSET_DP),
    )
}

private fun Preferences.toSettings(): CaptionSettings {
    val d = CaptionSettings()
    return validate(
        CaptionSettings(
            sourceLang = this[SettingsKeys.sourceLang] ?: d.sourceLang,
            targetLang = this[SettingsKeys.targetLang] ?: d.targetLang,
            fontSizeSp = this[SettingsKeys.fontSizeSp] ?: d.fontSizeSp,
            opacity = this[SettingsKeys.opacity] ?: d.opacity,
            bilingual = this[SettingsKeys.bilingual] ?: d.bilingual,
            modelSize = this[SettingsKeys.modelSize]
                ?.let { name -> ModelSize.entries.firstOrNull { it.name == name } }
                ?: d.modelSize,
            autoModel = this[SettingsKeys.autoModel] ?: d.autoModel,
            textColor = this[SettingsKeys.textColor] ?: d.textColor,
            plateColor = this[SettingsKeys.plateColor] ?: d.plateColor,
            showDiagnostics = this[SettingsKeys.showDiagnostics] ?: d.showDiagnostics,
            recordScreen = this[SettingsKeys.recordScreen] ?: d.recordScreen,
            translationOffsetDp = this[SettingsKeys.translationOffsetDp],
            originalOffsetDp = this[SettingsKeys.originalOffsetDp],
        ),
    )
}

/** Persists [CaptionSettings] in a DataStore; every read is validated. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    /** Validated settings; a DataStore [IOException] yields the defaults instead of failing the flow. */
    val settings: Flow<CaptionSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    /** The current validated settings. */
    suspend fun current(): CaptionSettings = settings.first()

    /** Atomically applies [transform] to the stored settings; the result is validated before saving. */
    suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
        store.edit { prefs ->
            val next = validate(transform(prefs.toSettings()))
            prefs[SettingsKeys.sourceLang] = next.sourceLang
            prefs[SettingsKeys.targetLang] = next.targetLang
            prefs[SettingsKeys.fontSizeSp] = next.fontSizeSp
            prefs[SettingsKeys.opacity] = next.opacity
            prefs[SettingsKeys.bilingual] = next.bilingual
            prefs[SettingsKeys.modelSize] = next.modelSize.name
            prefs[SettingsKeys.autoModel] = next.autoModel
            prefs[SettingsKeys.textColor] = next.textColor
            prefs[SettingsKeys.plateColor] = next.plateColor
            prefs[SettingsKeys.showDiagnostics] = next.showDiagnostics
            prefs[SettingsKeys.recordScreen] = next.recordScreen
            next.translationOffsetDp?.let { prefs[SettingsKeys.translationOffsetDp] = it } ?: prefs.remove(SettingsKeys.translationOffsetDp)
            next.originalOffsetDp?.let { prefs[SettingsKeys.originalOffsetDp] = it } ?: prefs.remove(SettingsKeys.originalOffsetDp)
        }
    }
}
