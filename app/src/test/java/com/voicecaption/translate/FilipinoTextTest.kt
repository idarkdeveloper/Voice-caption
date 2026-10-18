package com.voicecaption.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class FilipinoTextTest {
    @Test
    fun dropsPolitenessAndFillerWords() {
        assertEquals("Last 3 minutes na lang.", FilipinoText.prepare("Last 3 minutes na lang po."))
        assertEquals("Diba? Alam nyo naman yun.", FilipinoText.prepare("Diba? Alam nyo naman yun eh."))
    }

    @Test
    fun opoBecomesOo() {
        assertEquals("oo, excited tayo.", FilipinoText.prepare("Opo, excited po tayo."))
    }

    @Test
    fun keepsWordsThatMerelyContainTheParticles() {
        assertEquals("Hapon at ehersisyo", FilipinoText.prepare("Hapon at ehersisyo"))
    }

    @Test
    fun neverReturnsNothing() {
        assertEquals("Po.", FilipinoText.prepare("Po."))
    }
}
