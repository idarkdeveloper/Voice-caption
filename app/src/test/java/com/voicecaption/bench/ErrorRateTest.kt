package com.voicecaption.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorRateTest {
    @Test fun identicalIsZero() = assertEquals(0.0, WordErrorRate.compute("hello brave new world", "hello brave new world"), 1e-9)

    @Test fun oneSubstitutionInFourWords() =
        assertEquals(0.25, WordErrorRate.compute("hello brave new world", "hello brave old world"), 1e-9)

    @Test fun emptyHypothesisIsOne() = assertEquals(1.0, WordErrorRate.compute("hello world", ""), 1e-9)

    @Test fun bothEmptyIsZero() = assertEquals(0.0, WordErrorRate.compute("", ""), 1e-9)

    @Test fun emptyReferenceWithHypothesisIsOne() = assertEquals(1.0, WordErrorRate.compute("", "hello"), 1e-9)

    @Test fun caseAndPunctuationIgnored() =
        assertEquals(0.0, WordErrorRate.compute("Hello, World!", "hello world"), 1e-9)

    @Test fun insertionAndDeletionCount() {
        assertEquals(0.5, WordErrorRate.compute("a b", "a b c"), 1e-9)
        assertEquals(0.5, WordErrorRate.compute("a b", "a"), 1e-9)
    }

    @Test fun unicodeWordsAreTokenized() =
        assertEquals(0.0, WordErrorRate.compute("¿Cómo estás?", "como estás".replace("como", "cómo")), 1e-9)

    @Test fun errorRateUsesWerWhenReferenceHasSpaces() {
        val r = ErrorRate.compute("hello brave new world", "hello brave old world")
        assertEquals(ErrorRate.Metric.WER, r.metric)
        assertEquals(0.25, r.value, 1e-9)
    }

    @Test fun errorRateUsesCerWhenReferenceHasNoSpaces() {
        val r = ErrorRate.compute("こんにちは世界", "こんにちは世")
        assertEquals(ErrorRate.Metric.CER, r.metric)
        assertEquals(1.0 / 7, r.value, 1e-9)
    }

    @Test fun cerIgnoresPunctuation() =
        assertEquals(0.0, ErrorRate.compute("こんにちは、世界。", "こんにちは世界").value, 1e-9)
}
