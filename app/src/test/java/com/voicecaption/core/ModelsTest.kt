package com.voicecaption.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelsTest {
    @Test
    fun defaultSettingsAreReadable() {
        val s = CaptionSettings()
        assertEquals(18, s.fontSizeSp)
        assertEquals(0.8f, s.opacity, 0f)
        assertFalse(s.bilingual)
        assertEquals(ModelSize.BASE, s.modelSize)
    }
}
