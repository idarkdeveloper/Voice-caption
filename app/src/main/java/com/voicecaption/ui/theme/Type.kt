package com.voicecaption.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

/** System font; sizes follow the doc scale 12/14/16/20/24/32. */
internal val TransCapTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 32.sp, lineHeight = 40.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

