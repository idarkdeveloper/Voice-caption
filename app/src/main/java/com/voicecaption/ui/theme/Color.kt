package com.voicecaption.ui.theme

import androidx.compose.ui.graphics.Color

// Colors come from the generated TransCap palette (slate / electric blue / amber / orange).
// PROVISIONAL: docs/design/2026-10-17-android-ui-and-landing-page.md says there is no design
// system yet, so reconcile these with DESIGN.md once it exists. Text pairs below were chosen for
// at least 4.5:1 contrast; do not substitute lower-contrast values.

// Dark theme
internal val Slate950 = Color(0xFF090D18) // background and surface
internal val Slate900 = Color(0xFF1A1F2D) // cards, surfaceVariant
internal val Slate50 = Color(0xFFEAF2FF) // primary text
internal val Slate300 = Color(0xFF9CA4BA) // secondary text, outlines
internal val ElectricBlue300 = Color(0xFF6092FF) // primary on dark
internal val Orange300 = Color(0xFFFF753E) // error on dark

// Light theme
internal val White = Color(0xFFFFFFFF)
internal val Slate600 = Color(0xFF555D72) // secondary text, outlines
internal val ElectricBlue600 = Color(0xFF2439EE) // primary on light
internal val Red700 = Color(0xFFAE1B00) // error on light
internal val Slate25 = Color(0xFFF2F5FB) // light cards (provisional, not in the generated palette)

// Both themes
internal val Amber = Color(0xFFFFB21A) // accent; always paired with dark text
