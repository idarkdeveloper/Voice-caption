package com.voicecaption.service

/**
 * Normalizes a raw `BATTERY_PROPERTY_CAPACITY` reading to 0..100. Negative values (including
 * `Integer.MIN_VALUE`, which Android returns when the level is unknown) count as 100, so an
 * unknown battery never downgrades the model; values above 100 are capped.
 */
fun normalizeBatteryPercent(raw: Int): Int = if (raw < 0) 100 else raw.coerceAtMost(100)
