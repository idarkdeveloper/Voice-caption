package com.voicecaption.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapseRepeatsTest {
    @Test
    fun aLoopedPhraseIsKeptOnce() {
        val loop = "The amount of the amount of the amount of the amount of the amount of the amount of the amount of"
        assertEquals("The amount of", RecognizedText.collapseRepeats(loop))
    }

    @Test
    fun textBeforeAndAfterTheLoopIsKept() {
        assertEquals(
            "Well, the amount of and then",
            RecognizedText.collapseRepeats("Well, the amount of the amount of the amount of and then"),
        )
    }

    @Test
    fun aSingleWordLoopIsKeptTwice() {
        assertEquals("go go", RecognizedText.collapseRepeats("go go go go go go go"))
    }

    @Test
    fun normalRepetitionAndShortTextAreUntouched() {
        assertEquals("No, no, no, I said no.", RecognizedText.collapseRepeats("No, no, no, I said no."))
        assertEquals("Wait a minute.", RecognizedText.collapseRepeats("Wait a minute."))
        assertEquals("It is what it is.", RecognizedText.collapseRepeats("It is what it is."))
    }
}
