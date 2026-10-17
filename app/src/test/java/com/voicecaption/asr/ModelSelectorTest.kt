package com.voicecaption.asr

import com.voicecaption.core.ModelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectorTest {
    @Test
    fun hotDeviceFallsBackToBase() {
        assertEquals(ModelSize.BASE, ModelSelector.choose(ModelSize.SMALL, thermalStatus = 2, batteryPct = 80))
    }

    @Test
    fun lowBatteryFallsBackToBase() {
        assertEquals(ModelSize.BASE, ModelSelector.choose(ModelSize.SMALL, thermalStatus = 0, batteryPct = 10))
    }

    @Test
    fun batteryAtFifteenPercentKeepsPreferred() {
        assertEquals(ModelSize.SMALL, ModelSelector.choose(ModelSize.SMALL, thermalStatus = 0, batteryPct = 15))
    }

    @Test
    fun thermalLightKeepsPreferred() {
        assertEquals(ModelSize.SMALL, ModelSelector.choose(ModelSize.SMALL, thermalStatus = 1, batteryPct = 80))
    }

    @Test
    fun healthyDeviceKeepsPreferred() {
        assertEquals(ModelSize.SMALL, ModelSelector.choose(ModelSize.SMALL, thermalStatus = 0, batteryPct = 80))
        assertEquals(ModelSize.BASE, ModelSelector.choose(ModelSize.BASE, thermalStatus = 0, batteryPct = 80))
    }
}
