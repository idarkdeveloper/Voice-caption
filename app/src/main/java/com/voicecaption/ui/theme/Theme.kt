package com.voicecaption.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors: ColorScheme = darkColorScheme(
    primary = ElectricBlue300,
    onPrimary = Slate950,
    primaryContainer = ElectricBlue300,
    onPrimaryContainer = Slate950,
    secondary = Amber,
    onSecondary = Slate950,
    secondaryContainer = ElectricBlue300, // selected segment
    onSecondaryContainer = Slate950,
    tertiary = Amber,
    onTertiary = Slate950,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate950,
    onSurface = Slate50,
    surfaceVariant = Slate900,
    onSurfaceVariant = Slate300,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = Slate900,
    surfaceContainerHigh = Slate900,
    surfaceContainerHighest = Slate900,
    outline = Slate300,
    outlineVariant = Slate300,
    error = Orange300,
    onError = Slate950,
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = ElectricBlue600,
    onPrimary = White,
    primaryContainer = ElectricBlue600,
    onPrimaryContainer = White,
    secondary = Amber,
    onSecondary = Slate950,
    secondaryContainer = ElectricBlue600, // selected segment
    onSecondaryContainer = White,
    tertiary = Amber,
    onTertiary = Slate950,
    background = White,
    onBackground = Slate950,
    surface = White,
    onSurface = Slate950,
    surfaceVariant = Slate25,
    onSurfaceVariant = Slate600,
    surfaceContainerLowest = White,
    surfaceContainerLow = Slate25,
    surfaceContainer = Slate25,
    surfaceContainerHigh = Slate25,
    surfaceContainerHighest = Slate25,
    outline = Slate600,
    outlineVariant = Slate600,
    error = Red700,
    onError = White,
)

/** App theme: follows the system dark/light setting; no dynamic color (the palette is the brand). */
@Composable
fun CaptionlyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CaptionlyTypography,
        content = content,
    )
}
