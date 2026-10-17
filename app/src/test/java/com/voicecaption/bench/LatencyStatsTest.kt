package com.voicecaption.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyStatsTest {
    private val oneToTwenty = (1..20).map { it.toLong() }

    @Test fun medianOfOneToTwentyIsMeanOfMiddlePair() = assertEquals(10.5, LatencyStats.median(oneToTwenty), 1e-9)

    @Test fun p95IsNearestRank() = assertEquals(19L, LatencyStats.p95(oneToTwenty))

    @Test fun medianOfOddCountIsMiddle() = assertEquals(3.0, LatencyStats.median(listOf(5L, 1L, 3L)), 1e-9)

    @Test fun unsortedInputIsHandled() = assertEquals(19L, LatencyStats.p95(oneToTwenty.reversed()))

    @Test fun singleValue() {
        assertEquals(7.0, LatencyStats.median(listOf(7L)), 1e-9)
        assertEquals(7L, LatencyStats.p95(listOf(7L)))
    }

    @Test fun emptyListYieldsZero() {
        assertEquals(0.0, LatencyStats.median(emptyList()), 1e-9)
        assertEquals(0L, LatencyStats.p95(emptyList()))
    }

    @Test fun meanOfValues() {
        assertEquals(2.0, LatencyStats.mean(listOf(1.0, 2.0, 3.0)), 1e-9)
        assertEquals(0.0, LatencyStats.mean(emptyList()), 1e-9)
    }
}
