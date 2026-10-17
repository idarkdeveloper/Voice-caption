package com.voicecaption.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMathTest {
    @Test fun alphaBelowFloorIsRaisedToHalf() = assertEquals(128, OverlayMath.plateAlpha(0.2f))
    @Test fun alphaInRangeIsScaled() = assertEquals(204, OverlayMath.plateAlpha(0.8f))
    @Test fun alphaAboveOneIsClamped() = assertEquals(255, OverlayMath.plateAlpha(1.5f))
    @Test fun alphaNegativeUsesFloor() = assertEquals(128, OverlayMath.plateAlpha(-1f))
    @Test fun alphaNanUsesFloor() = assertEquals(128, OverlayMath.plateAlpha(Float.NaN))

    @Test fun clampKeepsValueInside() = assertEquals(300, OverlayMath.clampOffsetY(300, 0, 500))
    @Test fun clampRaisesToMin() = assertEquals(0, OverlayMath.clampOffsetY(-40, 0, 500))
    @Test fun clampLowersToMax() = assertEquals(500, OverlayMath.clampOffsetY(900, 0, 500))
    @Test fun clampWithInvertedRangeReturnsMin() = assertEquals(100, OverlayMath.clampOffsetY(300, 100, 50))

    @Test fun maxWidthIsNinetyPercent() = assertEquals(972, OverlayMath.maxPlateWidthPx(1080))
    @Test fun maxWidthIntegerExact() {
        assertEquals(2160, OverlayMath.maxPlateWidthPx(2400))
        assertEquals(972, OverlayMath.maxPlateWidthPx(1081))
        assertEquals(0, OverlayMath.maxPlateWidthPx(1))
    }
    @Test fun maxWidthNeverNegative() = assertEquals(0, OverlayMath.maxPlateWidthPx(-5))
}
