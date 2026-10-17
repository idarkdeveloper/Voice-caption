package com.voicecaption.bench

/** Summary statistics over millisecond durations. Empty input yields zeros. */
internal object LatencyStats {
    /** Median; the mean of the two middle values for an even count. */
    fun median(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
    }

    /** 95th percentile by the nearest-rank method (always an observed value). */
    fun p95(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val s = values.sorted()
        val rank = kotlin.math.ceil(0.95 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    /** Arithmetic mean. */
    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size
}
