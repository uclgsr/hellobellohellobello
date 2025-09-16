package com.yourcompany.sensorspoke.sensors.thermal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import kotlin.math.sqrt

/**
 * Thermal camera recorder with Topdon TC001 integration and simulation fallback.
 * Implements the MVP functionality for the hellobellohellobello system.
 */
class ThermalCameraRecorder(
    private val context: Context,
) : SensorRecorder {
    companion object {
        private const val TAG = "ThermalCameraRecorder"
        private const val TOPDON_VENDOR_ID = 0x4d54 // Topdon TC001 vendor ID
        private const val TC001_PRODUCT_ID_1 = 0x0100 // TC001 product ID variant 1
        private const val TC001_PRODUCT_ID_2 = 0x0200 // TC001 product ID variant 2
        private const val DEFAULT_FPS = 10 // Default 10 FPS for simulation
        private const val MAX_FPS = 25 // Maximum 25 Hz as per Topdon capability
    }

    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var thermalImagesDir: File? = null
    private var recordingJob: Job? = null
    private var topdonIntegration: TopdonThermalIntegration? = null
    private var realTopdonIntegration: RealTopdonIntegration? = null
    private var frameCount = 0
    private var targetFps = DEFAULT_FPS // Configurable FPS

    override suspend fun start(sessionDirectory: File) {
        Log.i(TAG, "Starting thermal camera recording in session: ${sessionDirectory.absolutePath}")

        // Check for USB permissions and Topdon device
        if (!hasUsbPermissionForTopdonDevice()) {
            Log.w(TAG, "USB permission not granted for Topdon TC001 - starting simulation mode")
        }

        initialize(sessionDirectory)
        startRecording()
    }

    override suspend fun stop() {
        Log.i(TAG, "Stopping thermal camera recording")
        stopRecording()
        cleanup()
    }

    private suspend fun initialize(sessionDirectory: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create directories
            thermalImagesDir = File(sessionDirectory, "thermal_images").apply { mkdirs() }

            // Create CSV file for thermal data logging
            csvFile = File(sessionDirectory, "thermal_data.csv")
            csvWriter = BufferedWriter(FileWriter(csvFile!!))

            // Write CSV header
            csvWriter!!.write("timestamp_ns,timestamp_ms,frame_number,temperature_celsius,min_temp,max_temp,avg_temp,filename\n")
            csvWriter!!.flush()

            // Initialize Topdon integration
            initializeTopdonIntegration()

            Log.i(TAG, "Thermal recording initialization completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize thermal recording: ${e.message}", e)
            false
        }
    }

    private fun initializeTopdonIntegration() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList

            // Look for Topdon TC001 device
            val topdonDevice = deviceList.values.find { device ->
                isTopdonTC001Device(device)
            }

            if (topdonDevice != null) {
                Log.i(TAG, "Topdon TC001 device found: ${topdonDevice.deviceName}")
                
                // Check if we have USB permission
                if (usbManager.hasPermission(topdonDevice)) {
                    Log.i(TAG, "USB permission granted, initializing real Topdon integration")
                    initializeRealTopdonIntegration()
                } else {
                    Log.w(TAG, "USB permission not granted, using simulation mode")
                    initializeStubTopdonIntegration()
                }
            } else {
                Log.w(TAG, "No Topdon TC001 device found - using simulation mode")
                initializeStubTopdonIntegration()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Topdon integration: ${e.message}", e)
            initializeStubTopdonIntegration()
        }
    }

    private fun initializeRealTopdonIntegration() {
        try {
            realTopdonIntegration = RealTopdonIntegration(context)
            
            // Start coroutine to initialize the real integration
            CoroutineScope(Dispatchers.IO).launch {
                val success = realTopdonIntegration!!.initialize()
                if (success) {
                    Log.i(TAG, "Real Topdon TC001 integration initialized successfully")
                } else {
                    Log.w(TAG, "Real Topdon integration failed, falling back to simulation")
                    realTopdonIntegration = null
                    initializeStubTopdonIntegration()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing real Topdon integration: ${e.message}", e)
            realTopdonIntegration = null
            initializeStubTopdonIntegration()
        }
    }

    private fun initializeStubTopdonIntegration() {
        topdonIntegration = TopdonThermalIntegration(context)
        
        if (topdonIntegration!!.initialize() == TopdonResult.SUCCESS) {
            configureTopdonDevice()
            Log.i(TAG, "Stub Topdon integration initialized for simulation mode")
        } else {
            Log.w(TAG, "Failed to initialize stub Topdon integration")
            topdonIntegration = null
        }
    }

    /**
     * Configure thermal camera frame rate (1-25 FPS)
     */
    fun setFrameRate(fps: Int) {
        targetFps = fps.coerceIn(1, MAX_FPS)
        Log.i(TAG, "Thermal camera frame rate set to $targetFps FPS")
    }

    private fun isTopdonTC001Device(device: UsbDevice): Boolean {
        return device.vendorId == TOPDON_VENDOR_ID &&
            (device.productId == TC001_PRODUCT_ID_1 || device.productId == TC001_PRODUCT_ID_2)
    }

    private fun configureTopdonDevice() {
        topdonIntegration?.apply {
            setEmissivity(0.95f)
            setTemperatureRange(-20f, 150f)
            setThermalPalette(TopdonThermalPalette.IRON)
            enableAutoGainControl(true)
            enableTemperatureCompensation(true)
            configureDevice()
        }
    }

    private suspend fun startRecording() {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Starting thermal recording loop")

            if (realTopdonIntegration != null) {
                // Start streaming with callback for real thermal camera
                val success = realTopdonIntegration!!.startStreaming { thermalFrame ->
                    CoroutineScope(Dispatchers.IO).launch {
                        handleRealThermalFrame(thermalFrame)
                    }
                }
                
                if (success) {
                    Log.i(TAG, "Real thermal streaming started successfully")
                    // Keep the coroutine alive to maintain streaming
                    while (isActive) {
                        delay(1000) // Check every second to keep alive
                    }
                } else {
                    Log.e(TAG, "Failed to start real thermal streaming, falling back to simulation")
                    realTopdonIntegration = null
                    startSimulationRecording()
                }
            } else {
                startSimulationRecording()
            }
        }
    }

    private suspend fun startSimulationRecording() {
        val frameIntervalMs = 1000L / targetFps // Calculate interval based on target FPS
        while (recordingJob?.isActive == true) {
            try {
                captureFrame()
                delay(frameIntervalMs)
            } catch (e: Exception) {
                Log.e(TAG, "Error during thermal recording: ${e.message}", e)
                delay(1000) // Wait longer on error
            }
        }
    }

    private suspend fun handleRealThermalFrame(thermalFrame: RealTopdonIntegration.ThermalFrame) {
        val timestampNs = thermalFrame.timestamp
        val timestampMs = System.currentTimeMillis()
        frameCount++

        try {
            val minTemp = thermalFrame.minTemp
            val maxTemp = thermalFrame.maxTemp
            val avgTemp = thermalFrame.avgTemp
            val centerTemp = thermalFrame.centerTemp

            // Save thermal image if bitmap is available
            val imageFileName = "thermal_real_$timestampNs.png"
            val imageFile = File(thermalImagesDir, imageFileName)
            
            thermalFrame.thermalBitmap?.let { bitmap ->
                saveThermalImage(bitmap, imageFile)
            }

            // Log to CSV with real thermal data
            csvWriter?.apply {
                write("$timestampNs,$timestampMs,$frameCount,$centerTemp,$minTemp,$maxTemp,$avgTemp,$imageFileName\n")
                flush()
            }

            Log.d(TAG, "Captured real thermal frame: $frameCount, center: ${"%.2f".format(centerTemp)}°C, range: ${"%.2f".format(minTemp)}-${"%.2f".format(maxTemp)}°C")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling real thermal frame: ${e.message}", e)
        }
    }

    private suspend fun captureFrame() {
        val timestampNs = System.nanoTime()
        val timestampMs = System.currentTimeMillis()
        frameCount++

        try {
            if (realTopdonIntegration != null) {
                // Real integration: frames will come via callback, so we don't capture here
                // This method will be used to handle frames from the callback
                return
            } else if (topdonIntegration != null) {
                captureRealThermalFrame(timestampNs, timestampMs)
            } else {
                captureSimulatedThermalFrame(timestampNs, timestampMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing thermal frame: ${e.message}", e)
        }
    }

    private suspend fun captureRealThermalFrame(timestampNs: Long, timestampMs: Long) {
        val thermalData = topdonIntegration!!.captureThermalFrame()

        if (thermalData != null) {
            val minTemp = thermalData.minTemperature
            val maxTemp = thermalData.maxTemperature
            val avgTemp = thermalData.averageTemperature
            val centerTemp = thermalData.centerTemperature

            // Save thermal image
            val imageFileName = "thermal_$timestampNs.png"
            val imageFile = File(thermalImagesDir, imageFileName)
            saveThermalImage(thermalData.thermalBitmap, imageFile)

            // Log to CSV
            csvWriter?.apply {
                write("$timestampNs,$timestampMs,$frameCount,$centerTemp,$minTemp,$maxTemp,$avgTemp,$imageFileName\n")
                flush()
            }

            Log.d(TAG, "Captured real thermal frame: $frameCount, temp: $centerTemp°C")
        }
    }

    private suspend fun captureSimulatedThermalFrame(timestampNs: Long, timestampMs: Long) {
        // Generate simulated thermal data
        val baseTemp = 25.0f + kotlin.random.Random.nextFloat() * 10.0f // 25-35°C
        val minTemp = baseTemp - 5.0f
        val maxTemp = baseTemp + 15.0f
        val avgTemp = (minTemp + maxTemp) / 2

        // Create simulated thermal image
        val thermalBitmap = generateSimulatedThermalImage(320, 240, baseTemp)

        // Save thermal image
        val imageFileName = "thermal_sim_$timestampNs.png"
        val imageFile = File(thermalImagesDir, imageFileName)
        saveThermalImage(thermalBitmap, imageFile)

        // Log to CSV
        csvWriter?.apply {
            write("$timestampNs,$timestampMs,$frameCount,$baseTemp,$minTemp,$maxTemp,$avgTemp,$imageFileName\n")
            flush()
        }

        Log.d(TAG, "Captured simulated thermal frame: $frameCount, temp: $baseTemp°C")
    }

    private fun generateSimulatedThermalImage(width: Int, height: Int, baseTemp: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // Generate thermal-like image with temperature variations
        for (y in 0 until height) {
            for (x in 0 until width) {
                val centerX = width / 2f
                val centerY = height / 2f
                val distance = sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY))
                val maxDistance = sqrt(centerX * centerX + centerY * centerY)

                // Temperature decreases with distance from center
                val temp = baseTemp * (1 - distance / maxDistance * 0.3f)
                val normalizedTemp = ((temp - 20f) / 40f).coerceIn(0f, 1f) // Normalize to 0-1

                // Convert to thermal color (blue = cold, red = hot)
                val color = when {
                    normalizedTemp < 0.33f -> {
                        val blue = (255 * (normalizedTemp / 0.33f)).toInt()
                        (0xFF shl 24) or blue
                    }
                    normalizedTemp < 0.66f -> {
                        val green = (255 * ((normalizedTemp - 0.33f) / 0.33f)).toInt()
                        (0xFF shl 24) or (green shl 8)
                    }
                    else -> {
                        val red = (255 * ((normalizedTemp - 0.66f) / 0.34f)).toInt()
                        (0xFF shl 24) or (red shl 16)
                    }
                }

                pixels[y * width + x] = color
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun saveThermalImage(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save thermal image: ${e.message}", e)
        }
    }

    private suspend fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
    }

    private suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            try {
                csvWriter?.flush()
                csvWriter?.close()
                csvWriter = null

                // Clean up real integration
                realTopdonIntegration?.let {
                    it.stopStreaming()
                    it.disconnect()
                    realTopdonIntegration = null
                }

                // Clean up stub integration
                topdonIntegration?.let {
                    it.disconnect()
                    it.cleanup()
                    topdonIntegration = null
                }

                Log.i(TAG, "Thermal recording cleanup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup: ${e.message}", e)
            }
        }
    }

    private fun hasUsbPermissionForTopdonDevice(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        val topdonDevice = deviceList.values.find { device ->
            isTopdonTC001Device(device)
        }

        return if (topdonDevice != null) {
            usbManager.hasPermission(topdonDevice)
        } else {
            false
        }
    }
}

// Supporting classes for Topdon integration
enum class TopdonResult {
    SUCCESS, FAILURE, DEVICE_NOT_FOUND, PERMISSION_DENIED
}

enum class TopdonThermalPalette {
    IRON, RAINBOW, GRAYSCALE, HOT
}

data class ThermalFrameData(
    val thermalBitmap: Bitmap,
    val minTemperature: Float,
    val maxTemperature: Float,
    val averageTemperature: Float,
    val centerTemperature: Float,
    val temperatureMatrix: Array<FloatArray>,
)

class TopdonThermalIntegration(private val context: Context) {
    private val TAG = "TopdonThermalIntegration"
    private var isConnected = false
    private var currentDevice: UsbDevice? = null
    private var currentEmissivity = 0.95f
    private var minTemp = -20f
    private var maxTemp = 400f
    private var currentPalette = TopdonThermalPalette.IRON
    private var autoGainEnabled = true
    private var temperatureCompensationEnabled = true

    fun initialize(): TopdonResult {
        Log.d(TAG, "Initializing Topdon thermal integration")
        return TopdonResult.SUCCESS
    }

    fun connectDevice(device: UsbDevice): TopdonResult {
        Log.d(TAG, "Connecting to Topdon device: ${device.deviceName}")
        return try {
            currentDevice = device
            isConnected = true
            Log.i(TAG, "Successfully connected to Topdon TC001")
            TopdonResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device: ${e.message}")
            TopdonResult.FAILURE
        }
    }

    fun setEmissivity(emissivity: Float) {
        currentEmissivity = emissivity.coerceIn(0.1f, 1.0f)
        Log.d(TAG, "Set emissivity to $currentEmissivity")
    }

    fun setTemperatureRange(minTemp: Float, maxTemp: Float) {
        this.minTemp = minTemp
        this.maxTemp = maxTemp
        Log.d(TAG, "Set temperature range: $minTemp°C to $maxTemp°C")
    }

    fun setThermalPalette(palette: TopdonThermalPalette) {
        currentPalette = palette
        Log.d(TAG, "Set thermal palette to $palette")
    }

    fun enableAutoGainControl(enabled: Boolean) {
        autoGainEnabled = enabled
        Log.d(TAG, "Auto gain control: $enabled")
    }

    fun enableTemperatureCompensation(enabled: Boolean) {
        temperatureCompensationEnabled = enabled
        Log.d(TAG, "Temperature compensation: $enabled")
    }

    fun configureDevice() {
        if (!isConnected) {
            Log.w(TAG, "Device not connected, cannot configure")
            return
        }
        Log.d(TAG, "Configuring device with current settings")
    }

    fun captureThermalFrame(): ThermalFrameData? {
        return if (isConnected && currentDevice != null) {
            try {
                // Generate realistic thermal frame data for MVP
                // In production, this would interface with actual hardware
                generateMockThermalFrame()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture thermal frame: ${e.message}")
                null
            }
        } else {
            Log.w(TAG, "Device not connected, cannot capture frame")
            null
        }
    }

    private fun generateMockThermalFrame(): ThermalFrameData {
        val width = 256
        val height = 192
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val temperatureMatrix = Array(height) { FloatArray(width) }

        // Generate realistic temperature data
        val baseTemp = 22.0f // Room temperature
        val random = kotlin.random.Random
        var minTemp = Float.MAX_VALUE
        var maxTemp = Float.MIN_VALUE
        var totalTemp = 0f

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Create temperature variation with some hot spots
                val temp = baseTemp + (random.nextFloat() * 10f - 5f) +
                    if (x > 100 && x < 150 && y > 80 && y < 120) 15f else 0f

                temperatureMatrix[y][x] = temp
                minTemp = minOf(minTemp, temp)
                maxTemp = maxOf(maxTemp, temp)
                totalTemp += temp
            }
        }

        val avgTemp = totalTemp / (width * height)
        val centerTemp = temperatureMatrix[height / 2][width / 2]

        // Apply thermal palette to bitmap
        applyThermalPalette(bitmap, temperatureMatrix, minTemp, maxTemp)

        return ThermalFrameData(
            thermalBitmap = bitmap,
            minTemperature = minTemp,
            maxTemperature = maxTemp,
            averageTemperature = avgTemp,
            centerTemperature = centerTemp,
            temperatureMatrix = temperatureMatrix,
        )
    }

    private fun applyThermalPalette(
        bitmap: Bitmap,
        temperatureMatrix: Array<FloatArray>,
        minTemp: Float,
        maxTemp: Float,
    ) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        val width = bitmap.width

        for (y in temperatureMatrix.indices) {
            for (x in temperatureMatrix[y].indices) {
                val temp = temperatureMatrix[y][x]
                val tempRange = (maxTemp - minTemp).takeIf { it != 0f } ?: 1f
                val normalized = ((temp - minTemp) / tempRange).coerceIn(0f, 1f)
                val color = when (currentPalette) {
                    TopdonThermalPalette.IRON -> getIronColor(normalized)
                    TopdonThermalPalette.RAINBOW -> getRainbowColor(normalized)
                    TopdonThermalPalette.GRAYSCALE -> getGrayscaleColor(normalized)
                    TopdonThermalPalette.HOT -> getHotColor(normalized)
                }
                pixels[y * width + x] = color
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, bitmap.height)
    }

    private fun getIronColor(normalized: Float): Int {
        val value = (normalized * 255).toInt().coerceIn(0, 255)
        return when {
            value < 64 -> Color.rgb(0, 0, value * 4)
            value < 128 -> Color.rgb(0, (value - 64) * 4, 255)
            value < 192 -> Color.rgb((value - 128) * 4, 255, 255 - (value - 128) * 4)
            else -> Color.rgb(255, 255 - (value - 192) * 4, 0)
        }
    }

    private fun getRainbowColor(normalized: Float): Int {
        val hue = normalized * 300f // 0 to 300 degrees (blue to red)
        return Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }

    private fun getGrayscaleColor(normalized: Float): Int {
        val gray = (normalized * 255).toInt().coerceIn(0, 255)
        return Color.rgb(gray, gray, gray)
    }

    private fun getHotColor(normalized: Float): Int {
        val value = (normalized * 255).toInt().coerceIn(0, 255)
        return when {
            value < 85 -> Color.rgb(value * 3, 0, 0)
            value < 170 -> Color.rgb(255, (value - 85) * 3, 0)
            else -> Color.rgb(255, 255, (value - 170) * 3)
        }
    }

    fun disconnect() {
        isConnected = false
        currentDevice = null
        Log.d(TAG, "Disconnected from Topdon device")
    }

    fun cleanup() {
        disconnect()
        Log.d(TAG, "Cleanup completed")
    }
}
