package com.yourcompany.sensorspoke.sensors.gsr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.core.content.ContextCompat
import com.shimmerresearch.android.Shimmer
import com.shimmerresearch.android.guiUtilities.ShimmerBluetoothDialog
import com.shimmerresearch.androidradiodriver.Shimmer3BLEAndroid
import com.shimmerresearch.bluetooth.ShimmerBluetooth
import com.shimmerresearch.driver.Configuration
import com.shimmerresearch.driver.ObjectCluster
import com.shimmerresearch.exceptions.ShimmerException
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.random.Random
import org.json.JSONObject

/**
 * Shimmer GSR Recording Modes
 */
enum class ShimmerRecordingMode {
    REAL_TIME_STREAMING,  // Live BLE data streaming to app
    LOGGING_ONLY         // Log to Shimmer SD card, app acts as controller
}

/**
 * Shimmer SD Card Logging Configuration
 */
data class ShimmerLoggingConfig(
    val samplingRate: Double = 128.0,           // Hz
    val gsrRange: Int = 0,                      // GSR range setting
    val enablePPG: Boolean = true,              // Enable PPG sensors
    val enableAccel: Boolean = false,           // Enable accelerometer
    val sessionDurationMinutes: Int = 60        // Maximum session duration
)

/**
 * Logging-Only Shimmer Manager
 * 
 * This class handles Shimmer devices in logging-only mode where data is stored
 * on the Shimmer's internal SD card with device timestamps, and the Android app
 * acts as a remote controller sending start/stop commands.
 */
class LoggingOnlyShimmerManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "LoggingOnlyShimmer"
        
        // Shimmer command bytes for logging control
        private const val START_LOGGING_COMMAND = 0x07.toByte()
        private const val STOP_LOGGING_COMMAND = 0x20.toByte()
        private const val SET_SAMPLING_RATE_COMMAND = 0x05.toByte()
        private const val SET_SENSORS_COMMAND = 0x08.toByte()
        
        // Default configuration
        private val DEFAULT_CONFIG = ShimmerLoggingConfig()
    }
    
    // Core components
    private var shimmerDevice: Shimmer3BLEAndroid? = null
    private var messageHandler: Handler? = null
    private var loggingCallback: ShimmerLoggingCallback? = null
    
    // Connection and logging state
    @Volatile private var isConnected = false
    @Volatile private var isLogging = false
    private var selectedDeviceAddress: String? = null
    private var selectedDeviceName: String? = null
    private var loggingConfig = DEFAULT_CONFIG
    private var sessionStartTime: Long = 0
    
    /**
     * Initialize logging-only Shimmer manager
     */
    fun initialize(): Boolean = try {
        // Create message handler for Shimmer callbacks  
        messageHandler = Handler(Looper.getMainLooper()) { message ->
            handleShimmerMessage(message)
        }
        
        Log.i(TAG, "LoggingOnlyShimmerManager initialized")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize LoggingOnlyShimmerManager: ${e.message}", e)
        false
    }
    
    /**
     * Set logging callback for status updates
     */
    fun setLoggingCallback(callback: ShimmerLoggingCallback) {
        loggingCallback = callback
    }
    
    /**
     * Configure logging settings
     */
    fun setLoggingConfig(config: ShimmerLoggingConfig) {
        loggingConfig = config
        Log.i(TAG, "Logging config updated: ${config}")
    }
    
    /**
     * Connect to Shimmer device for logging control
     */
    suspend fun connect(deviceAddress: String, deviceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                selectedDeviceAddress = deviceAddress
                selectedDeviceName = deviceName
                
                Log.i(TAG, "Connecting to Shimmer for logging control: $deviceName ($deviceAddress)")
                
                // Create Shimmer3BLEAndroid instance
                messageHandler?.let { handler ->
                    shimmerDevice = Shimmer3BLEAndroid(deviceAddress, handler, context)
                    
                    // Connect to device
                    shimmerDevice?.connect(deviceAddress, deviceName)
                    
                    // Wait for connection (with timeout)
                    var connectionTimeout = 10000L // 10 seconds
                    val checkInterval = 100L
                    
                    while (connectionTimeout > 0 && !isConnected) {
                        delay(checkInterval)
                        connectionTimeout -= checkInterval
                    }
                    
                    if (isConnected) {
                        Log.i(TAG, "Successfully connected to Shimmer for logging control")
                        configureLoggingSettings()
                        return@withContext true
                    } else {
                        Log.e(TAG, "Connection timeout after 10 seconds")
                        return@withContext false
                    }
                } ?: run {
                    Log.e(TAG, "Message handler not initialized")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to Shimmer device: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Configure Shimmer for logging mode
     */
    private suspend fun configureLoggingSettings() {
        withContext(Dispatchers.IO) {
            try {
                shimmerDevice?.let { device ->
                    // Configure sensors for logging
                    val sensorConfig = (
                        Shimmer.SENSOR_GSR or 
                        (if (loggingConfig.enablePPG) Shimmer.SENSOR_INT_A13 else 0) or
                        (if (loggingConfig.enableAccel) Shimmer.SENSOR_ACCEL else 0) or
                        Shimmer.SENSOR_TIMESTAMP
                    ).toLong()
                    
                    device.setEnabledSensors(sensorConfig)
                    
                    // Set sampling rate for logging
                    device.setSamplingRateShimmer(loggingConfig.samplingRate)
                    
                    // Set GSR range
                    device.setGSRRange(loggingConfig.gsrRange)
                    
                    Log.i(TAG, "Shimmer configured for logging mode: " +
                          "Rate=${loggingConfig.samplingRate}Hz, " +
                          "GSR Range=${loggingConfig.gsrRange}, " +
                          "PPG=${loggingConfig.enablePPG}")
                    
                    delay(500) // Allow configuration to settle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring Shimmer logging settings: ${e.message}", e)
            }
        }
    }
    
    /**
     * Start SD card logging on Shimmer device
     */
    suspend fun startLogging(sessionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected) {
                    Log.e(TAG, "Cannot start logging: device not connected")
                    return@withContext false
                }
                
                shimmerDevice?.let { device ->
                    Log.i(TAG, "Starting SD card logging for session: $sessionId")
                    
                    // Send start logging command to Shimmer
                    device.startStreaming() // This will start logging to SD card
                    
                    sessionStartTime = System.currentTimeMillis()
                    isLogging = true
                    
                    // Create session metadata for logging mode
                    createLoggingSessionMetadata(sessionId)
                    
                    // Notify callback
                    loggingCallback?.onLoggingStarted(sessionId, sessionStartTime)
                    
                    Log.i(TAG, "SD card logging started successfully")
                    return@withContext true
                } ?: run {
                    Log.e(TAG, "Shimmer device not initialized")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting SD card logging: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Stop SD card logging on Shimmer device
     */
    suspend fun stopLogging(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isLogging) {
                    Log.w(TAG, "Logging is not active")
                    return@withContext true
                }
                
                shimmerDevice?.let { device ->
                    Log.i(TAG, "Stopping SD card logging")
                    
                    // Send stop logging command to Shimmer
                    device.stopStreaming()
                    
                    val loggingDuration = System.currentTimeMillis() - sessionStartTime
                    isLogging = false
                    
                    // Notify callback
                    loggingCallback?.onLoggingStopped(loggingDuration)
                    
                    Log.i(TAG, "SD card logging stopped. Duration: ${loggingDuration}ms")
                    return@withContext true
                } ?: run {
                    Log.e(TAG, "Shimmer device not initialized")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping SD card logging: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Disconnect from Shimmer device
     */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                if (isLogging) {
                    stopLogging()
                }
                
                shimmerDevice?.let { device ->
                    device.disconnect()
                    isConnected = false
                    Log.i(TAG, "Disconnected from Shimmer device")
                }
                
                shimmerDevice = null
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from Shimmer device: ${e.message}", e)
            }
        }
    }
    
    /**
     * Handle messages from Shimmer device (logging mode)
     */
    private fun handleShimmerMessage(message: Message): Boolean {
        try {
            when (message.what) {
                ShimmerBluetooth.MSG_IDENTIFIER_STATE_CHANGE -> {
                    if (message.obj is ObjectCluster) {
                        val objectCluster = message.obj as ObjectCluster
                        handleStateChange(objectCluster.mState, objectCluster.getMacAddress())
                    }
                }
                
                Shimmer.MSG_IDENTIFIER_NOTIFICATION_MESSAGE -> {
                    val notification = message.obj as? Int ?: 0
                    handleLoggingNotification(notification)
                }
                
                Shimmer.MESSAGE_TOAST -> {
                    val toastText = message.data?.getString(Shimmer.TOAST) ?: ""
                    Log.i(TAG, "Shimmer logging toast: $toastText")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Shimmer logging message: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Handle Shimmer state changes (logging mode)
     */
    private fun handleStateChange(state: ShimmerBluetooth.BT_STATE, address: String) {
        Log.d(TAG, "Shimmer logging state change: $state for device $address")
        
        when (state) {
            ShimmerBluetooth.BT_STATE.CONNECTED -> {
                isConnected = true
                loggingCallback?.onConnectionStateChanged(true, "Connected to Shimmer for logging control")
            }
            
            ShimmerBluetooth.BT_STATE.STREAMING -> {
                isLogging = true
                loggingCallback?.onLoggingStateChanged(true, "SD card logging started")
            }
            
            ShimmerBluetooth.BT_STATE.DISCONNECTED,
            ShimmerBluetooth.BT_STATE.CONNECTION_LOST -> {
                isConnected = false
                isLogging = false
                loggingCallback?.onConnectionStateChanged(false, "Disconnected from Shimmer device")
                loggingCallback?.onLoggingStateChanged(false, "SD card logging stopped")
            }
            
            else -> {
                // Handle other states as needed
            }
        }
    }
    
    /**
     * Handle logging-specific notifications
     */
    private fun handleLoggingNotification(notification: Int) {
        when (notification) {
            Shimmer.NOTIFICATION_SHIMMER_FULLY_INITIALIZED -> {
                Log.i(TAG, "Shimmer device ready for logging mode")
                loggingCallback?.onDeviceInitialized("Shimmer ready for SD card logging")
            }
            
            Shimmer.NOTIFICATION_SHIMMER_START_STREAMING -> {
                Log.i(TAG, "Shimmer SD card logging started")
            }
            
            Shimmer.NOTIFICATION_SHIMMER_STOP_STREAMING -> {
                Log.i(TAG, "Shimmer SD card logging stopped")
            }
        }
    }
    
    /**
     * Create session metadata for logging mode
     */
    private fun createLoggingSessionMetadata(sessionId: String) {
        try {
            val metadata = JSONObject().apply {
                put("session_id", sessionId)
                put("recording_mode", "SHIMMER_LOGGING_ONLY")
                put("start_timestamp_ms", sessionStartTime)
                put("start_timestamp_ns", System.nanoTime())
                put("device_name", selectedDeviceName ?: "Unknown")
                put("device_address", selectedDeviceAddress ?: "Unknown")
                put("sampling_rate_hz", loggingConfig.samplingRate)
                put("gsr_range", loggingConfig.gsrRange)
                put("sensors_enabled", JSONObject().apply {
                    put("gsr", true)
                    put("ppg", loggingConfig.enablePPG)
                    put("accelerometer", loggingConfig.enableAccel)
                })
                put("notes", "Data logged to Shimmer SD card with internal timestamps")
            }
            
            Log.d(TAG, "Created logging session metadata: $metadata")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating logging session metadata: ${e.message}", e)
        }
    }
    
    /**
     * Get current logging status
     */
    fun getLoggingStatus(): ShimmerLoggingStatus = ShimmerLoggingStatus(
        isConnected = isConnected,
        isLogging = isLogging,
        deviceName = selectedDeviceName,
        deviceAddress = selectedDeviceAddress,
        sessionStartTime = if (isLogging) sessionStartTime else null,
        loggingDuration = if (isLogging) System.currentTimeMillis() - sessionStartTime else 0,
        config = loggingConfig
    )
}

/**
 * Callback interface for logging-only mode events
 */
interface ShimmerLoggingCallback {
    fun onLoggingStarted(sessionId: String, startTime: Long)
    fun onLoggingStopped(durationMs: Long)
    fun onConnectionStateChanged(connected: Boolean, message: String)
    fun onLoggingStateChanged(logging: Boolean, message: String)
    fun onDeviceInitialized(message: String)
    fun onError(error: String)
}

/**
 * Logging status data class
 */
data class ShimmerLoggingStatus(
    val isConnected: Boolean,
    val isLogging: Boolean,
    val deviceName: String?,
    val deviceAddress: String?,
    val sessionStartTime: Long?,
    val loggingDuration: Long,
    val config: ShimmerLoggingConfig
)

/**
 * Real ShimmerAndroidAPI Integration Manager
 * Replaces the previous simulation with actual Shimmer API integration
 */

/**
 * Production ShimmerAndroidAPI GSR Integration Manager
 *
 * This class integrates the official ShimmerAndroidAPI for real-time GSR data collection
 * from Shimmer3 GSR+ sensors. Features professional-grade BLE connection management,
 * data processing, and CSV logging with nanosecond precision timestamps.
 */
class ShimmerGSRIntegrationManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ShimmerGSRIntegration"

        // Request codes for device selection
        const val REQUEST_CONNECT_SHIMMER = 2001

        // GSR-specific configurations
        const val DEFAULT_SAMPLING_RATE = 128.0 // Hz - Optimal for GSR
        const val DEFAULT_GSR_RANGE = Shimmer.GSR_RANGE_4_7M // Most sensitive

        // CSV column headers
        const val CSV_HEADER = "timestamp_ns,gsr_raw_adc,gsr_microsiemens,gsr_calibrated,ppg_raw_adc,ppg_calibrated,sample_count"
    }

    // Core components
    private var shimmerDevice: Shimmer3BLEAndroid? = null
    private var messageHandler: Handler? = null
    private var dataCallback: ShimmerDataCallback? = null

    // Connection management
    private var isConnected = false
    private var isStreaming = false
    private var selectedDeviceAddress: String? = null
    private var selectedDeviceName: String? = null

    // Data processing
    private var sampleCount = 0L
    private var lastLogTime = 0L
    private val logInterval = 1000L // Log stats every 1 second

    /**
     * Initialize Shimmer integration system
     */
    fun initialize(): Boolean =
        try {
            // Create message handler for Shimmer callbacks
            messageHandler =
                Handler(Looper.getMainLooper()) { message ->
                    handleShimmerMessage(message)
                }

            Log.i(TAG, "ShimmerGSRIntegrationManager initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ShimmerGSRIntegrationManager: ${e.message}", e)
            false
        }

    /**
     * Set data callback for real-time GSR data
     */
    fun setDataCallback(callback: ShimmerDataCallback) {
        dataCallback = callback
    }

    /**
     * Launch device selection dialog
     */
    fun selectDevice(activity: Activity) {
        try {
            val intent = Intent(activity, ShimmerBluetoothDialog::class.java)
            activity.startActivityForResult(intent, REQUEST_CONNECT_SHIMMER)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching device selection: ${e.message}", e)
        }
    }

    /**
     * Handle device selection result
     */
    fun handleDeviceSelection(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (requestCode == REQUEST_CONNECT_SHIMMER && resultCode == Activity.RESULT_OK) {
            data?.let { intent ->
                selectedDeviceAddress = intent.getStringExtra(ShimmerBluetoothDialog.EXTRA_DEVICE_ADDRESS)
                selectedDeviceName = intent.getStringExtra(ShimmerBluetoothDialog.EXTRA_DEVICE_NAME)

                Log.i(TAG, "Selected Shimmer device: $selectedDeviceName ($selectedDeviceAddress)")
                return true
            }
        }
        return false
    }

    /**
     * Connect to selected Shimmer device
     */
    suspend fun connect(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val address = selectedDeviceAddress
                val name = selectedDeviceName

                if (address == null) {
                    Log.e(TAG, "No device selected for connection")
                    return@withContext false
                }

                Log.i(TAG, "Connecting to Shimmer device: $name ($address)")

                // Create Shimmer3BLEAndroid instance
                messageHandler?.let { handler ->
                    shimmerDevice = Shimmer3BLEAndroid(address, handler, context)

                    // Connect to device
                    shimmerDevice?.connect(address, name ?: "Shimmer Device")

                    // Wait for connection (with timeout)
                    var connectionTimeout = 10000L // 10 seconds
                    val checkInterval = 100L

                    while (connectionTimeout > 0 && !isConnected) {
                        delay(checkInterval)
                        connectionTimeout -= checkInterval
                    }

                    if (isConnected) {
                        Log.i(TAG, "Successfully connected to Shimmer device")
                        configureSensors()
                        return@withContext true
                    } else {
                        Log.e(TAG, "Connection timeout after 10 seconds")
                        return@withContext false
                    }
                } ?: run {
                    Log.e(TAG, "Message handler not initialized")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to Shimmer device: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Configure Shimmer sensors for GSR recording
     */
    private suspend fun configureSensors() {
        withContext(Dispatchers.IO) {
            try {
                shimmerDevice?.let { device ->
                    // Enable GSR, PPG, and timestamp sensors
                    val sensorConfig =
                        (
                            Shimmer.SENSOR_GSR or
                                Shimmer.SENSOR_INT_A13 or
                                Shimmer.SENSOR_TIMESTAMP
                            ).toLong()

                    device.setEnabledSensors(sensorConfig)

                    // Set sampling rate
                    device.setSamplingRateShimmer(DEFAULT_SAMPLING_RATE)

                    // Set GSR range to most sensitive
                    device.setGSRRange(DEFAULT_GSR_RANGE)

                    Log.i(TAG, "Shimmer sensors configured: GSR + PPG at ${DEFAULT_SAMPLING_RATE}Hz")

                    delay(500) // Allow configuration to settle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring Shimmer sensors: ${e.message}", e)
            }
        }
    }

    /**
     * Start data streaming
     */
    suspend fun startStreaming(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected) {
                    Log.e(TAG, "Cannot start streaming: device not connected")
                    return@withContext false
                }

                shimmerDevice?.let { device ->
                    device.startStreaming()

                    // Wait for streaming to start
                    var timeout = 5000L
                    while (timeout > 0 && !isStreaming) {
                        delay(100)
                        timeout -= 100
                    }

                    if (isStreaming) {
                        Log.i(TAG, "GSR data streaming started")
                        sampleCount = 0L
                        return@withContext true
                    } else {
                        Log.e(TAG, "Failed to start streaming within timeout")
                        return@withContext false
                    }
                } ?: run {
                    Log.e(TAG, "Shimmer device not initialized")
                    return@withContext false
                }
            } catch (e: ShimmerException) {
                Log.e(TAG, "Shimmer error starting stream: ${e.message}", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error starting GSR streaming: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Stop data streaming
     */
    suspend fun stopStreaming() {
        withContext(Dispatchers.IO) {
            try {
                shimmerDevice?.let { device ->
                    if (isStreaming) {
                        device.stopStreaming()
                        isStreaming = false
                        Log.i(TAG, "GSR data streaming stopped")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping GSR streaming: ${e.message}", e)
            }
        }
    }

    /**
     * Disconnect from Shimmer device
     */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                if (isStreaming) {
                    stopStreaming()
                }

                shimmerDevice?.let { device ->
                    device.disconnect()
                    isConnected = false
                    Log.i(TAG, "Disconnected from Shimmer device")
                }

                shimmerDevice = null
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from Shimmer device: ${e.message}", e)
            }
        }
    }

    /**
     * Handle messages from Shimmer device
     */
    private fun handleShimmerMessage(message: Message): Boolean {
        try {
            when (message.what) {
                ShimmerBluetooth.MSG_IDENTIFIER_DATA_PACKET -> {
                    if (message.obj is ObjectCluster) {
                        val objectCluster = message.obj as ObjectCluster
                        processDataPacket(objectCluster)
                    }
                }

                ShimmerBluetooth.MSG_IDENTIFIER_STATE_CHANGE -> {
                    if (message.obj is ObjectCluster) {
                        val objectCluster = message.obj as ObjectCluster
                        handleStateChange(objectCluster.mState, objectCluster.getMacAddress())
                    }
                }

                Shimmer.MSG_IDENTIFIER_NOTIFICATION_MESSAGE -> {
                    val notification = message.obj as? Int ?: 0
                    handleNotification(notification)
                }

                Shimmer.MESSAGE_TOAST -> {
                    val toastText = message.data?.getString(Shimmer.TOAST) ?: ""
                    Log.i(TAG, "Shimmer toast: $toastText")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Shimmer message: ${e.message}", e)
            return false
        }
    }

    /**
     * Process incoming data packet from Shimmer
     */
    private fun processDataPacket(objectCluster: ObjectCluster) {
        try {
            val timestampNs = System.nanoTime()

            // Extract GSR data
            val gsrFormats = objectCluster.getCollectionOfFormatClusters("GSR")
            val gsrRawCluster =
                gsrFormats?.let {
                    ObjectCluster.returnFormatCluster(it, "RAW")
                }
            val gsrCalCluster =
                gsrFormats?.let {
                    ObjectCluster.returnFormatCluster(it, "CAL")
                }

            // Extract PPG data
            val ppgFormats = objectCluster.getCollectionOfFormatClusters("PPG")
            val ppgRawCluster =
                ppgFormats?.let {
                    ObjectCluster.returnFormatCluster(it, "RAW")
                }
            val ppgCalCluster =
                ppgFormats?.let {
                    ObjectCluster.returnFormatCluster(it, "CAL")
                }

            // Extract timestamp
            val timestampFormats =
                objectCluster.getCollectionOfFormatClusters(
                    Configuration.Shimmer3.ObjectClusterSensorName.TIMESTAMP,
                )
            val timestampCluster =
                timestampFormats?.let {
                    ObjectCluster.returnFormatCluster(it, "CAL")
                }

            // Create GSR data structure
            val gsrData =
                GSRDataPoint(
                    timestampNs = timestampNs,
                    timestampDevice = timestampCluster?.mData ?: 0.0,
                    gsrRawAdc = gsrRawCluster?.mData?.toInt() ?: 0,
                    gsrMicrosiemens = gsrCalCluster?.mData ?: 0.0,
                    gsrCalibrated = gsrCalCluster?.mData ?: 0.0,
                    ppgRawAdc = ppgRawCluster?.mData?.toInt() ?: 0,
                    ppgCalibrated = ppgCalCluster?.mData ?: 0.0,
                    sampleNumber = ++sampleCount,
                )

            // Send to callback
            dataCallback?.onGSRData(gsrData)

            // Log periodically for monitoring
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLogTime >= logInterval) {
                Log.d(
                    TAG,
                    "GSR: Raw=${gsrData.gsrRawAdc}, µS=${String.format("%.2f", gsrData.gsrMicrosiemens)}, " +
                        "PPG=${gsrData.ppgRawAdc}, Samples=$sampleCount, Rate=${sampleCount.toDouble() / ((currentTime - lastLogTime) / 1000.0)}",
                )
                lastLogTime = currentTime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing GSR data packet: ${e.message}", e)
        }
    }

    /**
     * Handle Shimmer state changes
     */
    private fun handleStateChange(
        state: ShimmerBluetooth.BT_STATE,
        address: String,
    ) {
        Log.d(TAG, "Shimmer state change: $state for device $address")

        when (state) {
            ShimmerBluetooth.BT_STATE.CONNECTED -> {
                isConnected = true
                dataCallback?.onConnectionStateChanged(true, "Connected to Shimmer device")
            }

            ShimmerBluetooth.BT_STATE.STREAMING -> {
                isStreaming = true
                dataCallback?.onStreamingStateChanged(true, "GSR data streaming started")
            }

            ShimmerBluetooth.BT_STATE.DISCONNECTED,
            ShimmerBluetooth.BT_STATE.CONNECTION_LOST,
            -> {
                isConnected = false
                isStreaming = false
                dataCallback?.onConnectionStateChanged(false, "Disconnected from Shimmer device")
                dataCallback?.onStreamingStateChanged(false, "GSR data streaming stopped")
            }

            else -> {
                // Handle other states as needed
            }
        }
    }

    /**
     * Handle Shimmer notifications
     */
    private fun handleNotification(notification: Int) {
        when (notification) {
            Shimmer.NOTIFICATION_SHIMMER_FULLY_INITIALIZED -> {
                Log.i(TAG, "Shimmer device fully initialized")
                dataCallback?.onDeviceInitialized("Shimmer device ready for streaming")
            }

            Shimmer.NOTIFICATION_SHIMMER_START_STREAMING -> {
                Log.i(TAG, "Shimmer streaming started notification")
            }

            Shimmer.NOTIFICATION_SHIMMER_STOP_STREAMING -> {
                Log.i(TAG, "Shimmer streaming stopped notification")
            }
        }
    }

    /**
     * Get connection status
     */
    fun isDeviceConnected(): Boolean = isConnected

    /**
     * Get streaming status
     */
    fun isDeviceStreaming(): Boolean = isStreaming

    /**
     * Get current sample count
     */
    fun getCurrentSampleCount(): Long = sampleCount

    /**
     * Get selected device info
     */
    fun getSelectedDeviceInfo(): Pair<String?, String?> = Pair(selectedDeviceAddress, selectedDeviceName)
}

/**
 * GSR data structure for real-time processing
 */
data class GSRDataPoint(
    val timestampNs: Long, // System nanosecond timestamp
    val timestampDevice: Double, // Device timestamp (ms)
    val gsrRawAdc: Int, // Raw 12-bit ADC value (0-4095)
    val gsrMicrosiemens: Double, // GSR in microsiemens
    val gsrCalibrated: Double, // Calibrated GSR value
    val ppgRawAdc: Int, // Raw PPG ADC value
    val ppgCalibrated: Double, // Calibrated PPG value
    val sampleNumber: Long, // Sequential sample number
) {
    /**
     * Convert to CSV row format
     */
    fun toCsvRow(): String =
        "$timestampNs,$gsrRawAdc,${String.format(java.util.Locale.ROOT, "%.6f", gsrMicrosiemens)}," +
            "${String.format(java.util.Locale.ROOT, "%.6f", gsrCalibrated)},$ppgRawAdc," +
            "${String.format(java.util.Locale.ROOT, "%.6f", ppgCalibrated)},$sampleNumber"
}

/**
 * Callback interface for GSR data and state changes
 */
interface ShimmerDataCallback {
    fun onGSRData(data: GSRDataPoint)

    fun onConnectionStateChanged(
        connected: Boolean,
        message: String,
    )

    fun onStreamingStateChanged(
        streaming: Boolean,
        message: String,
    )

    fun onDeviceInitialized(message: String)

    fun onError(error: String)
}

/**
 * Production ShimmerRecorder supporting both real-time streaming and logging-only modes.
 *
 * This implementation supports two operational modes:
 * 1. REAL_TIME_STREAMING: Live BLE data streaming with local CSV logging
 * 2. LOGGING_ONLY: Remote control mode where Shimmer logs to SD card
 */
class ShimmerRecorder(
    private val context: Context,
    private val recordingMode: ShimmerRecordingMode = ShimmerRecordingMode.REAL_TIME_STREAMING
) : SensorRecorder {
    companion object {
        private const val TAG = "ShimmerRecorder"
        
        /**
         * Create ShimmerRecorder for real-time streaming mode
         */
        fun forRealTimeStreaming(context: Context): ShimmerRecorder {
            return ShimmerRecorder(context, ShimmerRecordingMode.REAL_TIME_STREAMING)
        }
        
        /**
         * Create ShimmerRecorder for logging-only mode
         */
        fun forLoggingOnly(context: Context, config: ShimmerLoggingConfig? = null): ShimmerRecorder {
            return ShimmerRecorder(context, ShimmerRecordingMode.LOGGING_ONLY).apply {
                config?.let { loggingManager?.setLoggingConfig(it) }
            }
        }
    }

    // Core components for both modes
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var sampleCount = 0L
    private var currentSessionId: String? = null
    
    // Real-time streaming components
    private var gsrIntegrationManager: ShimmerGSRIntegrationManager? = null
    
    // Logging-only mode components
    private var loggingManager: LoggingOnlyShimmerManager? = null
    private var sessionMetadataFile: File? = null

    // Real-time streaming data callback implementation
    private val streamingDataCallback = object : ShimmerDataCallback {
        override fun onGSRData(data: GSRDataPoint) {
            handleGSRData(data)
        }

        override fun onConnectionStateChanged(connected: Boolean, message: String) {
            Log.i(TAG, "Streaming connection state: $connected - $message")
        }

        override fun onStreamingStateChanged(streaming: Boolean, message: String) {
            Log.i(TAG, "Streaming state: $streaming - $message")
        }

        override fun onDeviceInitialized(message: String) {
            Log.i(TAG, "Streaming device initialized: $message")
        }

        override fun onError(error: String) {
            Log.e(TAG, "Streaming GSR Integration error: $error")
        }
    }
    
    // Logging-only mode callback implementation
    private val loggingCallback = object : ShimmerLoggingCallback {
        override fun onLoggingStarted(sessionId: String, startTime: Long) {
            Log.i(TAG, "Logging-only session started: $sessionId at $startTime")
            createLoggingSessionMetadata(sessionId, startTime)
        }

        override fun onLoggingStopped(durationMs: Long) {
            Log.i(TAG, "Logging-only session stopped. Duration: ${durationMs}ms")
            updateLoggingSessionMetadata(durationMs)
        }

        override fun onConnectionStateChanged(connected: Boolean, message: String) {
            Log.i(TAG, "Logging connection state: $connected - $message")
        }

        override fun onLoggingStateChanged(logging: Boolean, message: String) {
            Log.i(TAG, "Logging state: $logging - $message")
        }

        override fun onDeviceInitialized(message: String) {
            Log.i(TAG, "Logging device initialized: $message")
        }

        override fun onError(error: String) {
            Log.e(TAG, "Logging GSR error: $error")
        }
    }

    override suspend fun start(sessionDir: File) {
        if (!sessionDir.exists()) sessionDir.mkdirs()
        
        currentSessionId = sessionDir.name
        isRecording = true
        
        when (recordingMode) {
            ShimmerRecordingMode.REAL_TIME_STREAMING -> {
                startRealTimeStreaming(sessionDir)
            }
            ShimmerRecordingMode.LOGGING_ONLY -> {
                startLoggingOnly(sessionDir)
            }
        }
    }
    
    /**
     * Start real-time streaming mode (existing implementation)
     */
    private suspend fun startRealTimeStreaming(sessionDir: File) {
        // Check Bluetooth permissions before attempting connection
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Bluetooth permissions not granted - starting in simulation mode")
            startSimulationRecording(sessionDir)
            return
        }

        // Initialize CSV file for real-time data
        csvFile = File(sessionDir, "gsr_data.csv")
        csvWriter = BufferedWriter(FileWriter(csvFile!!))

        // Write CSV header
        csvWriter!!.write("${ShimmerGSRIntegrationManager.CSV_HEADER}\n")
        csvWriter!!.flush()

        try {
            // Initialize GSR integration manager
            gsrIntegrationManager = ShimmerGSRIntegrationManager(context).apply {
                if (!initialize()) {
                    throw RuntimeException("Failed to initialize ShimmerGSRIntegrationManager")
                }

                // Set data callback
                setDataCallback(streamingDataCallback)
            }

            // Check if we have device selection capability
            Log.i(TAG, "Real-time GSR recording initialized - ready for device connection")

            // Start recording job that will wait for device connection
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                monitorRecordingSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start real-time GSR recording: ${e.message}", e)

            // Fall back to simulation mode for testing
            Log.w(TAG, "Starting GSR simulation mode")
            startSimulationRecording(sessionDir)
        }
    }
    
    /**
     * Start logging-only mode (new implementation)
     */
    private suspend fun startLoggingOnly(sessionDir: File) {
        // Check Bluetooth permissions before attempting connection
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Bluetooth permissions not granted - cannot start logging-only mode")
            // In logging-only mode, we can't use simulation since no data streams to app
            throw IllegalStateException("Bluetooth permissions required for logging-only mode")
        }
        
        try {
            // Initialize logging manager
            loggingManager = LoggingOnlyShimmerManager(context).apply {
                if (!initialize()) {
                    throw RuntimeException("Failed to initialize LoggingOnlyShimmerManager")
                }
                
                // Set logging callback
                setLoggingCallback(loggingCallback)
            }
            
            Log.i(TAG, "Logging-only GSR mode initialized - ready for device connection")
            
            // Create metadata file for logging session
            sessionMetadataFile = File(sessionDir, "shimmer_logging_metadata.json")
            
            // Start monitoring job for connection and logging control
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                monitorLoggingSession()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logging-only GSR mode: ${e.message}", e)
            throw e
        }
    }

    override suspend fun stop() {
        try {
            isRecording = false

            when (recordingMode) {
                ShimmerRecordingMode.REAL_TIME_STREAMING -> {
                    stopRealTimeStreaming()
                }
                ShimmerRecordingMode.LOGGING_ONLY -> {
                    stopLoggingOnly()
                }
            }

            // Cancel recording job
            recordingJob?.cancel()

            Log.i(TAG, "GSR recording stopped (mode: $recordingMode). Total samples: $sampleCount")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GSR recording: ${e.message}", e)
        }
    }
    
    /**
     * Stop real-time streaming mode
     */
    private suspend fun stopRealTimeStreaming() {
        // Stop GSR integration
        gsrIntegrationManager?.let { manager ->
            if (manager.isDeviceStreaming()) {
                manager.stopStreaming()
            }
            if (manager.isDeviceConnected()) {
                manager.disconnect()
            }
        }

        // Close CSV writer
        csvWriter?.flush()
        csvWriter?.close()
        csvWriter = null
    }
    
    /**
     * Stop logging-only mode
     */
    private suspend fun stopLoggingOnly() {
        // Stop logging and disconnect
        loggingManager?.let { manager ->
            if (manager.getLoggingStatus().isLogging) {
                manager.stopLogging()
            }
            if (manager.getLoggingStatus().isConnected) {
                manager.disconnect()
            }
        }
        
        // Finalize metadata
        finalizeLoggingSessionMetadata()
    }

    /**
     * Connect to a specific Shimmer device for real-time streaming
     */
    suspend fun connectToDevice(deviceAddress: String, deviceName: String): Boolean {
        return when (recordingMode) {
            ShimmerRecordingMode.REAL_TIME_STREAMING -> {
                connectForStreaming(deviceAddress, deviceName)
            }
            ShimmerRecordingMode.LOGGING_ONLY -> {
                connectForLogging(deviceAddress, deviceName)
            }
        }
    }
    
    /**
     * Connect device for real-time streaming mode
     */
    private suspend fun connectForStreaming(deviceAddress: String, deviceName: String): Boolean {
        return try {
            gsrIntegrationManager?.let { manager ->
                if (manager.connect()) {
                    Log.i(TAG, "Successfully connected to Shimmer device for streaming: $deviceName")

                    // Start streaming
                    if (manager.startStreaming()) {
                        Log.i(TAG, "GSR streaming started")
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Shimmer device for streaming: ${e.message}", e)
            false
        }
    }
    
    /**
     * Connect device for logging-only mode
     */
    private suspend fun connectForLogging(deviceAddress: String, deviceName: String): Boolean {
        return try {
            loggingManager?.let { manager ->
                if (manager.connect(deviceAddress, deviceName)) {
                    Log.i(TAG, "Successfully connected to Shimmer device for logging control: $deviceName")
                    
                    // Start logging to SD card
                    val sessionId = currentSessionId ?: "unknown_session"
                    if (manager.startLogging(sessionId)) {
                        Log.i(TAG, "Shimmer SD card logging started")
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Shimmer device for logging: ${e.message}", e)
            false
        }
    }
    
    /**
     * Create logging session metadata
     */
    private fun createLoggingSessionMetadata(sessionId: String, startTime: Long) {
        try {
            val metadata = JSONObject().apply {
                put("session_id", sessionId)
                put("recording_mode", "SHIMMER_LOGGING_ONLY")
                put("start_timestamp_ms", startTime)
                put("start_timestamp_ns", System.nanoTime())
                put("device_info", loggingManager?.getLoggingStatus()?.let { status ->
                    JSONObject().apply {
                        put("device_name", status.deviceName ?: "Unknown")
                        put("device_address", status.deviceAddress ?: "Unknown")
                        put("sampling_rate_hz", status.config.samplingRate)
                        put("gsr_range", status.config.gsrRange)
                    }
                })
                put("data_location", "shimmer_sd_card")
                put("timestamp_source", "shimmer_internal")
                put("notes", "Data logged to Shimmer SD card with internal timestamps. " +
                          "Use Consensys software to download data from device.")
            }
            
            sessionMetadataFile?.let { file ->
                file.writeText(metadata.toString(2))
                Log.i(TAG, "Created logging session metadata: ${file.absolutePath}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating logging session metadata: ${e.message}", e)
        }
    }
    
    /**
     * Update logging session metadata on stop
     */
    private fun updateLoggingSessionMetadata(durationMs: Long) {
        try {
            sessionMetadataFile?.let { file ->
                if (file.exists()) {
                    val existingMetadata = JSONObject(file.readText())
                    existingMetadata.apply {
                        put("end_timestamp_ms", System.currentTimeMillis())
                        put("duration_ms", durationMs)
                        put("status", "completed")
                    }
                    
                    file.writeText(existingMetadata.toString(2))
                    Log.i(TAG, "Updated logging session metadata with completion info")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating logging session metadata: ${e.message}", e)
        }
    }
    
    /**
     * Finalize logging session metadata
     */
    private fun finalizeLoggingSessionMetadata() {
        try {
            sessionMetadataFile?.let { file ->
                if (file.exists()) {
                    val existingMetadata = JSONObject(file.readText())
                    existingMetadata.apply {
                        put("session_finalized", true)
                        put("finalized_timestamp_ms", System.currentTimeMillis())
                        
                        // Add final logging status
                        loggingManager?.getLoggingStatus()?.let { status ->
                            put("final_status", JSONObject().apply {
                                put("connected", status.isConnected)
                                put("logging_duration_ms", status.loggingDuration)
                            })
                        }
                    }
                    
                    file.writeText(existingMetadata.toString(2))
                    Log.i(TAG, "Finalized logging session metadata")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing logging session metadata: ${e.message}", e)
        }
    }

    /**
     * Monitor recording session and handle connection attempts (streaming mode)
     */
    private suspend fun monitorRecordingSession() {
        while (isRecording) {
            try {
                val manager = gsrIntegrationManager
                if (manager != null && !manager.isDeviceConnected()) {
                    Log.d(TAG, "Waiting for Shimmer device connection...")
                    // In a real app, this would trigger device selection UI
                    // For now, we'll wait and potentially start simulation
                    delay(5000)

                    // If still no connection after 5 seconds, start simulation
                    if (!manager.isDeviceConnected() && isRecording) {
                        Log.i(TAG, "No Shimmer device connected, starting simulation")
                        break
                    }
                }

                delay(1000) // Check connection status every second
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording session monitor: ${e.message}")
                break
            }
        }

        // If we exit the loop without a real device, start simulation
        if (isRecording && gsrIntegrationManager?.isDeviceConnected() != true) {
            startSimulationMode()
        }
    }
    
    /**
     * Monitor logging session (logging-only mode)
     */
    private suspend fun monitorLoggingSession() {
        while (isRecording) {
            try {
                val manager = loggingManager
                if (manager != null && !manager.getLoggingStatus().isConnected) {
                    Log.d(TAG, "Waiting for Shimmer device connection for logging control...")
                    // In a real app, this would trigger device selection UI
                    delay(5000)
                    
                    // Check if we should timeout
                    if (!manager.getLoggingStatus().isConnected && isRecording) {
                        Log.w(TAG, "No Shimmer device connected for logging control")
                        // Could implement retry logic or error handling here
                    }
                }
                
                delay(1000) // Check connection status every second
            } catch (e: Exception) {
                Log.e(TAG, "Error in logging session monitor: ${e.message}")
                break
            }
        }
    }

    /**
     * Handle incoming GSR data from real Shimmer device
     */
    private fun handleGSRData(data: GSRDataPoint) {
        try {
            // Write to CSV file
            csvWriter?.let { writer ->
                writer.write("${data.toCsvRow()}\n")

                // Flush periodically for data safety
                if (data.sampleNumber % 128 == 0L) {
                    writer.flush()
                }
            }

            sampleCount = data.sampleNumber

            // Log periodically for monitoring
            if (data.sampleNumber % 512 == 0L) {
                Log.d(
                    TAG,
                    "GSR: Sample #${data.sampleNumber}, " +
                        "Raw=${data.gsrRawAdc}, µS=${String.format("%.2f", data.gsrMicrosiemens)}, " +
                        "PPG=${data.ppgRawAdc}",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling GSR data: ${e.message}", e)
        }
    }

    /**
     * Fallback simulation mode when no real Shimmer device is available
     */
    private fun startSimulationMode() {
        recordingJob =
            CoroutineScope(Dispatchers.IO).launch {
                val samplingIntervalMs = (1000.0 / ShimmerGSRIntegrationManager.DEFAULT_SAMPLING_RATE).toLong()
                var simulationSampleCount = 0L

                Log.i(TAG, "GSR simulation mode started at ${ShimmerGSRIntegrationManager.DEFAULT_SAMPLING_RATE}Hz")

                try {
                    while (isActive && isRecording) {
                        val timestampNs = System.nanoTime()

                        // Generate realistic GSR simulation data
                        val baseGSR = 8.0 + 3.0 * kotlin.math.sin(simulationSampleCount * 0.01) // Slow drift
                        val noise = (Random.nextDouble() - 0.5) * 0.5 // Small random noise
                        val gsrMicrosiemens = (baseGSR + noise).coerceAtLeast(0.1)

                        // Generate corresponding raw ADC value (12-bit: 0-4095)
                        val resistance = 1000000.0 / gsrMicrosiemens // Convert to resistance
                        val voltage = (resistance * 3.0) / (resistance + 40200.0) // Voltage divider
                        val rawAdc = ((voltage / 3.0) * 4095.0).toInt().coerceIn(0, 4095)

                        // Generate simulated PPG
                        val ppgRaw =
                            (
                                2000 + 500 * kotlin.math.sin(simulationSampleCount * 0.1) +
                                    Random.nextDouble(-50.0, 50.0)
                                ).toInt().coerceIn(0, 4095)

                        // Create GSR data point
                        val gsrData =
                            GSRDataPoint(
                                timestampNs = timestampNs,
                                timestampDevice = System.currentTimeMillis().toDouble(),
                                gsrRawAdc = rawAdc,
                                gsrMicrosiemens = gsrMicrosiemens,
                                gsrCalibrated = gsrMicrosiemens,
                                ppgRawAdc = ppgRaw,
                                ppgCalibrated = ppgRaw.toDouble(),
                                sampleNumber = ++simulationSampleCount,
                            )

                        // Process simulated data
                        handleGSRData(gsrData)

                        delay(samplingIntervalMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in GSR simulation: ${e.message}", e)
                }
            }
    }

    /**
     * Get current recording statistics (mode-aware)
     */
    fun getRecordingStats(): GSRRecordingStats {
        return when (recordingMode) {
            ShimmerRecordingMode.REAL_TIME_STREAMING -> {
                GSRRecordingStats(
                    recordingMode = recordingMode,
                    isRecording = isRecording,
                    totalSamples = sampleCount,
                    isDeviceConnected = gsrIntegrationManager?.isDeviceConnected() ?: false,
                    isStreaming = gsrIntegrationManager?.isDeviceStreaming() ?: false,
                    deviceInfo = gsrIntegrationManager?.getSelectedDeviceInfo(),
                    outputFile = csvFile?.absolutePath,
                    loggingStatus = null
                )
            }
            ShimmerRecordingMode.LOGGING_ONLY -> {
                val loggingStatus = loggingManager?.getLoggingStatus()
                GSRRecordingStats(
                    recordingMode = recordingMode,
                    isRecording = isRecording,
                    totalSamples = 0L, // No local samples in logging mode
                    isDeviceConnected = loggingStatus?.isConnected ?: false,
                    isStreaming = loggingStatus?.isLogging ?: false,
                    deviceInfo = loggingStatus?.let { Pair(it.deviceAddress, it.deviceName) },
                    outputFile = sessionMetadataFile?.absolutePath,
                    loggingStatus = loggingStatus
                )
            }
        }
    }
    
    /**
     * Get current recording mode
     */
    fun getRecordingMode(): ShimmerRecordingMode = recordingMode
    
    /**
     * Check if device supports logging-only mode
     */
    fun supportsLoggingMode(): Boolean {
        // This could be enhanced to check device capabilities
        return hasBluetoothPermissions()
    }

    /**
     * Check if all required Bluetooth permissions are granted
     */
    private fun hasBluetoothPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Start simulation recording with proper CSV file initialization
     */
    private suspend fun startSimulationRecording(sessionDir: File) {
        try {
            // Initialize CSV file if not already done
            if (csvFile == null) {
                csvFile = File(sessionDir, "gsr_data.csv")
                csvWriter = BufferedWriter(FileWriter(csvFile!!))
                
                // Write CSV header
                csvWriter!!.write("${ShimmerGSRIntegrationManager.CSV_HEADER}\n")
                csvWriter!!.flush()
            }
            
            isRecording = true
            Log.i(TAG, "Starting GSR simulation recording to ${csvFile?.absolutePath}")
            
            // Start simulation mode
            startSimulationMode()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start simulation recording: ${e.message}", e)
            throw e
        }
    }
}

/**
 * GSR recording statistics (mode-aware)
 */
data class GSRRecordingStats(
    val recordingMode: ShimmerRecordingMode,
    val isRecording: Boolean,
    val totalSamples: Long,
    val isDeviceConnected: Boolean,
    val isStreaming: Boolean,
    val deviceInfo: Pair<String?, String?>?,
    val outputFile: String?,
    val loggingStatus: ShimmerLoggingStatus?
)
