package com.yourcompany.sensorspoke.sensors.thermal

import android.graphics.Bitmap
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException

/**
 * ThermalCameraRecorder scaffold for Topdon TC001 thermal camera integration.
 * Phase 2: create CSV file, thermal image files, and prepare interfaces; full SDK streaming will be added
 * when the Topdon SDK artifact is available. This keeps the app buildable and
 * creates the expected file structure during local testing.
 */
class ThermalCameraRecorder : SensorRecorder {
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var thermalImagesDir: File? = null

    override suspend fun start(sessionDir: File) {
        // Ensure directory and CSV file with header
        if (!sessionDir.exists()) sessionDir.mkdirs()
        
        // Create thermal images directory for IR image files
        thermalImagesDir = File(sessionDir, "thermal_images").apply { mkdirs() }
        
        csvFile = File(sessionDir, "thermal.csv")
        try {
            csvWriter = BufferedWriter(FileWriter(csvFile!!, true))
            if (csvFile!!.length() == 0L) {
                // Write header per spec: timestamp_ns,image_filename,w,h,min_temp,max_temp
                csvWriter!!.write("timestamp_ns,image_filename,w,h,min_temp_celsius,max_temp_celsius\n")
                csvWriter!!.flush()
            }
        } catch (e: IOException) {
            throw e
        }
        // Write basic metadata file to align with spec (placeholder until SDK integration)
        try {
            val meta = File(sessionDir, "thermal_metadata.json")
            if (!meta.exists()) {
                val json =
                    "{" +
                        "\"sensor\":\"Topdon TC001\"," +
                        "\"width\":256," +
                        "\"height\":192," +
                        "\"emissivity\":0.95," +
                        "\"format\":\"thermal_png\"," +
                        "\"notes\":\"Thermal images saved as PNG files with CSV index\"" +
                        "}"
                meta.writeText(json)
            }
        } catch (_: Exception) {
        }
        // TODO(Phase 2+): Initialize Topdon SDK, request USB permission, and stream frames.
        // For now, we do not generate rows without the actual device.
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
        thermalImagesDir = null
    }

    /**
     * Save thermal data as both image file and CSV entry.
     * This is a placeholder implementation until Topdon SDK is integrated.
     * 
     * @param timestamp_ns Nanosecond timestamp
     * @param thermalData 2D array of temperature values in Celsius
     * @param width Frame width (typically 256)
     * @param height Frame height (typically 192)
     */
    suspend fun saveThermalFrame(
        timestamp_ns: Long,
        thermalData: Array<FloatArray>,
        width: Int = 256,
        height: Int = 192
    ) {
        val imageFilename = "thermal_$timestamp_ns.png"
        val imageFile = File(thermalImagesDir, imageFilename)
        
        // Save thermal data as PNG image (false color representation)
        val thermalBitmap = createThermalBitmap(thermalData, width, height)
        saveImageAsPng(thermalBitmap, imageFile)
        
        // Calculate temperature range for CSV
        var minTemp = Float.MAX_VALUE
        var maxTemp = Float.MIN_VALUE
        thermalData.forEach { row ->
            row.forEach { temp ->
                if (temp < minTemp) minTemp = temp
                if (temp > maxTemp) maxTemp = temp
            }
        }
        
        // Write CSV entry
        try {
            csvWriter?.apply {
                write("$timestamp_ns,$imageFilename,$width,$height,$minTemp,$maxTemp\n")
                flush()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Create a false color bitmap representation of thermal data
     */
    private fun createThermalBitmap(thermalData: Array<FloatArray>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Simple false color mapping (blue = cold, red = hot)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val temp = thermalData[y][x]
                // Normalize temperature to 0-255 range (assuming 0-50°C range)
                val normalized = ((temp / 50.0f) * 255).toInt().coerceIn(0, 255)
                val red = normalized
                val green = (normalized * 0.5f).toInt()
                val blue = 255 - normalized
                
                val color = android.graphics.Color.rgb(red, green, blue)
                bitmap.setPixel(x, y, color)
            }
        }
        
        return bitmap
    }

    /**
     * Save bitmap as PNG file
     */
    private fun saveImageAsPng(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
            // Handle error silently for now
        }
    }
}
