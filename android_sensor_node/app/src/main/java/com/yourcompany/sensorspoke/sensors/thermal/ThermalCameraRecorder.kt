package com.yourcompany.sensorspoke.sensors.thermal

import com.yourcompany.sensorspoke.sensors.SensorRecorder
import com.yourcompany.sensorspoke.utils.TimeManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * ThermalCameraRecorder scaffold for Topdon TC001 thermal camera integration.
 * Phase 2: create CSV file and prepare interfaces; full SDK streaming will be added
 * when the Topdon SDK artifact is available. This keeps the app buildable and
 * creates the expected file structure during local testing.
 */
class ThermalCameraRecorder : SensorRecorder {

    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null

    override suspend fun start(sessionDir: File) {
        // Ensure directory and CSV file with header
        if (!sessionDir.exists()) sessionDir.mkdirs()
        csvFile = File(sessionDir, "thermal.csv")
        try {
            csvWriter = BufferedWriter(FileWriter(csvFile!!, true))
            if (csvFile!!.length() == 0L) {
                // Write header: timestamp_nanos followed by 256x192 = 49152 pixel columns placeholder
                csvWriter!!.write("timestamp_nanos")
                for (i in 0 until 49152) {
                    csvWriter!!.write(",p${i}")
                }
                csvWriter!!.write("\n")
                csvWriter!!.flush()
            }
        } catch (e: IOException) {
            throw e
        }
        // TODO(Phase 2+): Initialize Topdon SDK, request USB permission, and stream frames.
        // For now, we do not generate rows without the actual device.
    }

    override suspend fun stop() {
        try { csvWriter?.flush() } catch (_: Exception) {}
        try { csvWriter?.close() } catch (_: Exception) {}
        csvWriter = null
    }
}
