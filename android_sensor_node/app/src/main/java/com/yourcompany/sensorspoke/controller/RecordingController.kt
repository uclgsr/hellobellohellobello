package com.yourcompany.sensorspoke.controller

import android.content.Context
import com.yourcompany.sensorspoke.sensors.SensorRecorder

/**
 * RecordingController coordinates start/stop across sensor recorders.
 * Phase 1: placeholder only.
 */
class RecordingController(private val context: Context) {
    private val recorders = mutableListOf<SensorRecorder>()

    fun register(recorder: SensorRecorder) {
        recorders.add(recorder)
    }

    suspend fun startAll() {
        for (r in recorders) r.start()
    }

    suspend fun stopAll() {
        for (r in recorders) r.stop()
    }
}
