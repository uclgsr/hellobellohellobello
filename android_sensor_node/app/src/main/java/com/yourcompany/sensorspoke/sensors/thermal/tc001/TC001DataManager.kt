package com.yourcompany.sensorspoke.sensors.thermal.tc001

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.yourcompany.sensorspoke.sensors.thermal.TopdonThermalPalette
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * TC001DataManager - Enhanced thermal data processing
 *
 * Handles real-time thermal data from TC001 camera with IRCamera-inspired
 * data processing and temperature analysis capabilities
 */
class TC001DataManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "TC001DataManager"
        private const val THERMAL_FRAME_SIZE = 256 * 192 * 2 // 16-bit thermal data
        private const val TEMPERATURE_SCALE_FACTOR = 0.04f // K per LSB
        private const val TEMPERATURE_OFFSET = 273.15f // Kelvin to Celsius
    }

    // Thermal data streams
    private val _thermalFrame = MutableLiveData<ByteArray>()
    val thermalFrame: LiveData<ByteArray> = _thermalFrame

    private val _processedImage = MutableLiveData<ByteArray>()
    val processedImage: LiveData<ByteArray> = _processedImage

    private val _temperatureData = MutableLiveData<TC001TemperatureData>()
    val temperatureData: LiveData<TC001TemperatureData> = _temperatureData

    // Enhanced thermal bitmap output
    private val _thermalBitmap = MutableLiveData<android.graphics.Bitmap>()
    val thermalBitmap: LiveData<android.graphics.Bitmap> = _thermalBitmap

    // Processing parameters
    private val _currentPalette = MutableLiveData<TopdonThermalPalette>(TopdonThermalPalette.IRON)
    val currentPalette: LiveData<TopdonThermalPalette> = _currentPalette

    private val _emissivity = MutableLiveData<Float>(0.95f)
    val emissivity: LiveData<Float> = _emissivity

    private val _temperatureRange = MutableLiveData<Pair<Float, Float>>(-20f to 150f)
    val temperatureRange: LiveData<Pair<Float, Float>> = _temperatureRange

    // Processing scope
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isProcessing = false

    /**
     * Start thermal data processing
     */
    fun startProcessing() {
        if (isProcessing) {
            Log.w(TAG, "Processing already active")
            return
        }

        isProcessing = true
        Log.i(TAG, "Starting TC001 thermal data processing")

        processingScope.launch {
            simulateThermalDataStream()
        }
    }

    /**
     * Stop thermal data processing
     */
    fun stopProcessing() {
        isProcessing = false
        Log.i(TAG, "Stopping TC001 thermal data processing")
    }

    /**
     * Simulate thermal data stream from TC001
     * This would be replaced with actual TC001 SDK integration
     */
    private suspend fun simulateThermalDataStream() {
        while (isProcessing && processingScope.isActive) {
            try {
                // Generate simulated thermal frame data
                val thermalData = generateSimulatedThermalFrame()
                _thermalFrame.postValue(thermalData)

                // Process thermal data for temperature analysis
                val tempData = analyzeThermalFrame(thermalData)
                _temperatureData.postValue(tempData)

                // Generate processed image with current palette
                val processedImg = applyThermalPalette(thermalData, _currentPalette.value!!)
                _processedImage.postValue(processedImg)

                // Generate thermal bitmap for UI display
                val thermalBitmap = generateThermalBitmap(processedImg)
                _thermalBitmap.postValue(thermalBitmap)

                delay(33) // ~30 FPS
            } catch (e: Exception) {
                Log.e(TAG, "Error in thermal data processing", e)
                delay(1000) // Retry after error
            }
        }
    }

    /**
     * Generate simulated thermal frame data
     */
    private fun generateSimulatedThermalFrame(): ByteArray {
        val frameData = ByteArray(THERMAL_FRAME_SIZE)
        val buffer = ByteBuffer.wrap(frameData)

        // Generate realistic thermal gradient
        for (y in 0 until 192) {
            for (x in 0 until 256) {
                // Create a temperature gradient with some noise
                val baseTemp = 25.0f + (x / 256.0f) * 30.0f // 25°C to 55°C range
                val noise = (Math.random() * 2.0f - 1.0f) // ±1°C noise
                val tempCelsius = baseTemp + noise

                // Convert to raw sensor value
                val tempKelvin = tempCelsius + TEMPERATURE_OFFSET
                val rawValue = (tempKelvin / TEMPERATURE_SCALE_FACTOR).toInt().coerceIn(0, 65535)

                buffer.putShort((rawValue and 0xFFFF).toShort())
            }
        }

        return frameData
    }

    /**
     * Analyze thermal frame for temperature statistics
     */
    private fun analyzeThermalFrame(frameData: ByteArray): TC001TemperatureData {
        val buffer = ByteBuffer.wrap(frameData)
        var minTemp = Float.MAX_VALUE
        var maxTemp = Float.MIN_VALUE
        var sumTemp = 0.0f
        var pixelCount = 0

        while (buffer.hasRemaining()) {
            val rawValue = buffer.short.toInt() and 0xFFFF
            val tempKelvin = rawValue * TEMPERATURE_SCALE_FACTOR
            val tempCelsius = tempKelvin - TEMPERATURE_OFFSET

            minTemp = minOf(minTemp, tempCelsius)
            maxTemp = maxOf(maxTemp, tempCelsius)
            sumTemp += tempCelsius
            pixelCount++
        }

        val avgTemp = sumTemp / pixelCount
        val centerTemp = avgTemp // Simplified center temperature

        return TC001TemperatureData(
            minTemperature = minTemp,
            maxTemperature = maxTemp,
            avgTemperature = avgTemp,
            centerTemperature = centerTemp,
            emissivity = _emissivity.value ?: 0.95f,
        )
    }

    /**
     * Apply thermal palette to raw thermal data
     */
    private fun applyThermalPalette(
        frameData: ByteArray,
        palette: TopdonThermalPalette,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(frameData)
        val processedData = ByteArray(256 * 192 * 3) // RGB output
        var outputIndex = 0

        val (minRange, maxRange) = _temperatureRange.value ?: (-20f to 150f)

        while (buffer.hasRemaining()) {
            val rawValue = buffer.short.toInt() and 0xFFFF
            val tempKelvin = rawValue * TEMPERATURE_SCALE_FACTOR
            val tempCelsius = tempKelvin - TEMPERATURE_OFFSET

            // Normalize temperature to 0-1 range
            val normalizedTemp = ((tempCelsius - minRange) / (maxRange - minRange)).coerceIn(0f, 1f)

            // Apply palette
            val (r, g, b) =
                when (palette) {
                    TopdonThermalPalette.IRON -> applyIronPalette(normalizedTemp)
                    TopdonThermalPalette.RAINBOW -> applyRainbowPalette(normalizedTemp)
                    TopdonThermalPalette.GRAYSCALE -> applyGrayscalePalette(normalizedTemp)
                    else -> applyIronPalette(normalizedTemp)
                }

            processedData[outputIndex++] = r.toByte()
            processedData[outputIndex++] = g.toByte()
            processedData[outputIndex++] = b.toByte()
        }

        return processedData
    }

    private fun applyIronPalette(normalized: Float): Triple<Int, Int, Int> {
        // Iron palette: black -> red -> yellow -> white
        return when {
            normalized < 0.25f -> {
                val t = normalized * 4
                Triple((t * 255).toInt(), 0, 0)
            }
            normalized < 0.5f -> {
                val t = (normalized - 0.25f) * 4
                Triple(255, (t * 255).toInt(), 0)
            }
            normalized < 0.75f -> {
                val t = (normalized - 0.5f) * 4
                Triple(255, 255, (t * 255).toInt())
            }
            else -> {
                Triple(255, 255, 255)
            }
        }
    }

    private fun applyRainbowPalette(normalized: Float): Triple<Int, Int, Int> {
        // Rainbow palette
        val hue = normalized * 300f // 0 to 300 degrees
        return hsvToRgb(hue, 1f, 1f)
    }

    private fun applyGrayscalePalette(normalized: Float): Triple<Int, Int, Int> {
        val gray = (normalized * 255).toInt()
        return Triple(gray, gray, gray)
    }

    private fun hsvToRgb(
        h: Float,
        s: Float,
        v: Float,
    ): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1 - Math.abs(((h / 60f) % 2) - 1))
        val m = v - c

        val (r1, g1, b1) =
            when {
                h < 60 -> Triple(c, x, 0f)
                h < 120 -> Triple(x, c, 0f)
                h < 180 -> Triple(0f, c, x)
                h < 240 -> Triple(0f, x, c)
                h < 300 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }

        return Triple(
            ((r1 + m) * 255).toInt(),
            ((g1 + m) * 255).toInt(),
            ((b1 + m) * 255).toInt(),
        )
    }

    /**
     * Generate thermal bitmap from processed RGB data
     */
    private fun generateThermalBitmap(processedRgbData: ByteArray): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(256, 192, android.graphics.Bitmap.Config.ARGB_8888)

        var pixelIndex = 0
        for (y in 0 until 192) {
            for (x in 0 until 256) {
                val r = processedRgbData[pixelIndex++].toInt() and 0xFF
                val g = processedRgbData[pixelIndex++].toInt() and 0xFF
                val b = processedRgbData[pixelIndex++].toInt() and 0xFF

                val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bitmap.setPixel(x, y, color)
            }
        }

        return bitmap
    }

    /**
     * Update processing parameters
     */
    fun updatePalette(palette: TopdonThermalPalette) {
        _currentPalette.value = palette
    }

    fun updateEmissivity(emissivity: Float) {
        _emissivity.value = emissivity.coerceIn(0.1f, 1.0f)
    }

    fun updateTemperatureRange(
        minTemp: Float,
        maxTemp: Float,
    ) {
        if (minTemp < maxTemp) {
            _temperatureRange.value = minTemp to maxTemp
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        isProcessing = false
        processingScope.cancel()
    }
}

/**
 * Thermal temperature analysis data
 */
data class TC001TemperatureData(
    val minTemperature: Float,
    val maxTemperature: Float,
    val avgTemperature: Float,
    val centerTemperature: Float,
    val emissivity: Float,
    val timestamp: Long = System.currentTimeMillis(),
)
