package com.yourcompany.sensorspoke.sensors

/**
 * Common interface for all sensor recorders.
 * All I/O should be done using Kotlin coroutines.
 */
interface SensorRecorder {
    suspend fun start()
    suspend fun stop()
}
