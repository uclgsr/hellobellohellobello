package com.yourcompany.sensorspoke.utils

/**
 * TimeManager provides access to a monotonic time source to timestamp data.
 * Phase 1 exposes a simple API; future phases may add NTP-like sync support.
 */
object TimeManager {
    /**
     * Returns a monotonic timestamp in nanoseconds.
     */
    @JvmStatic
    fun nowNanos(): Long = System.nanoTime()
}
