package com.yourcompany.sensorspoke.sensors.thermal

import com.yourcompany.sensorspoke.sensors.SensorRecorder

/**
 * Placeholder for Topdon TC001 thermal camera integration.
 * Phase 1: scaffold only. Future phases will integrate the Topdon SDK.
 */
class ThermalCameraRecorder : SensorRecorder {
    override suspend fun start() {
        // TODO(Phase3): Initialize Topdon SDK and start frame callbacks
    }

    override suspend fun stop() {
        // TODO(Phase3): Stop and release Topdon SDK resources
    }
}
