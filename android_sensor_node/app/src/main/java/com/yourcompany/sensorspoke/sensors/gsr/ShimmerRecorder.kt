package com.yourcompany.sensorspoke.sensors.gsr

import com.yourcompany.sensorspoke.sensors.SensorRecorder
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * ShimmerRecorder stub for Phase 2 local recording structure.
 *
 * This class prepares a CSV file to log GSR (in microsiemens) and raw PPG values with
 * monotonic nanosecond timestamps. Integration with the official ShimmerAndroidAPI will
 * be added in the next iteration to actually connect over BLE and parse incoming packets.
 *
 * Critical: Conversion must use 12-bit ADC resolution (0-4095). The helper function
 * [convertGsrToMicroSiemens] documents the approach and will be used when real data arrives.
 */
class ShimmerRecorder : SensorRecorder {
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null

    override suspend fun start(sessionDir: File) {
        if (!sessionDir.exists()) sessionDir.mkdirs()
        csvFile = File(sessionDir, "gsr.csv")
        try {
            csvWriter = BufferedWriter(FileWriter(csvFile!!, true))
            if (csvFile!!.length() == 0L) {
                csvWriter!!.write("timestamp_ns,gsr_microsiemens,ppg_raw\n")
                csvWriter!!.flush()
            }
        } catch (e: IOException) {
            throw e
        }
        // TODO: Initialize ShimmerAndroidAPI, connect via BLE, send start (0x07) and handle notifications.
        // For now, this stub does not write sample rows without a device.
    }

    override suspend fun stop() {
        try {
            csvWriter?.flush()
        } catch (_: Exception) {
        }
        try {
            csvWriter?.close()
        } catch (_: Exception) {
        }
        csvWriter = null
    }

    /**
     * Converts a raw ADC value (0..4095 for 12-bit) to skin conductance in microSiemens (uS).
     *
     * Note: The exact conversion depends on the Shimmer GSR+ gain range and resistor network.
     * When integrated with the official API, the selected range (e.g., 10k/100k/680k/4.7M)
     * must be accounted for. This function shows the general structure and uses a placeholder
     * scale factor pending range detection from the device configuration.
     *
     * rawAdc: Raw 12-bit ADC reading.
     * vRef: ADC reference voltage (e.g., 3.0V or 3.3V depending on hardware), default 3.0V.
     * rangeScale: Placeholder mapping from voltage to microSiemens given the selected range.
     */
    fun convertGsrToMicroSiemens(
        rawAdc: Int,
        vRef: Double = 3.0,
        rangeScale: Double = 1.0,
    ): Double {
        val clamped = rawAdc.coerceIn(0, 4095)
        val voltage = (clamped / 4095.0) * vRef
        // Placeholder: actual conversion uses I = V/R and G = 1/R (Siemens), then microSiemens.
        // rangeScale encapsulates (1/R_range)*scaling to microSiemens based on Shimmer range.
        val microSiemens = voltage * rangeScale
        return microSiemens
    }
}
