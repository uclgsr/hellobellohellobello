package com.yourcompany.sensorspoke.sensors.rgb

import com.yourcompany.sensorspoke.sensors.SensorRecorder

/**
 * Placeholder implementation for Phase 1. In later phases, this will use
 * CameraX to record MP4 and capture high-res JPEG frames.
 */
class RgbCameraRecorder : SensorRecorder {
    override suspend fun start() {
        // TODO(Phase2): Initialize CameraX and start recording/streaming
    }

    override suspend fun stop() {
        // TODO(Phase2): Stop CameraX and release resources
    }
}
