# Shimmer3 GSR Usage Examples

This document provides practical examples of using both streaming and logging-only modes with the Shimmer3 GSR+ sensor integration.

## Real-Time Streaming Mode Examples

### Basic Real-Time Streaming
```kotlin
class ExampleActivity : AppCompatActivity() {
    private lateinit var shimmerRecorder: ShimmerRecorder
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create shimmer recorder for real-time streaming
        shimmerRecorder = ShimmerRecorder.forRealTimeStreaming(this)
    }
    
    private suspend fun startStreamingSession() {
        val sessionDir = File(filesDir, "sessions/streaming_${System.currentTimeMillis()}")
        
        try {
            // Start recording (will use simulation if no device connected)
            shimmerRecorder.start(sessionDir)
            
            // Monitor recording stats
            monitorRecordingStats()
            
        } catch (e: Exception) {
            Log.e("Example", "Failed to start streaming session", e)
        }
    }
    
    private fun monitorRecordingStats() {
        lifecycleScope.launch {
            while (shimmerRecorder.getRecordingStats().isRecording) {
                val stats = shimmerRecorder.getRecordingStats()
                Log.d("Example", "Samples: ${stats.totalSamples}, Connected: ${stats.isDeviceConnected}")
                delay(1000)
            }
        }
    }
}
```

### Real-Time Streaming with Device Selection
```kotlin
class StreamingWithDeviceSelection : AppCompatActivity() {
    
    private fun connectToSpecificDevice() {
        lifecycleScope.launch {
            val deviceAddress = "00:06:66:12:34:56"
            val deviceName = "Shimmer3-GSR-001"
            
            val success = shimmerRecorder.connectToDevice(deviceAddress, deviceName)
            
            if (success) {
                Log.i("Example", "Connected to Shimmer device successfully")
                // Device will start streaming automatically
            } else {
                Log.w("Example", "Failed to connect, using simulation mode")
            }
        }
    }
}
```

## Logging-Only Mode Examples

### Basic Logging-Only Session
```kotlin
class LoggingOnlyExample : AppCompatActivity() {
    private lateinit var shimmerRecorder: ShimmerRecorder
    
    private fun setupLoggingMode() {
        // Create custom logging configuration
        val config = ShimmerLoggingConfig(
            samplingRate = 128.0,      // 128 Hz sampling
            gsrRange = 0,              // Most sensitive GSR range
            enablePPG = true,          // Include PPG sensors
            enableAccel = false,       // No accelerometer needed
            sessionDurationMinutes = 30 // 30-minute max session
        )
        
        // Create shimmer recorder for logging-only mode
        shimmerRecorder = ShimmerRecorder.forLoggingOnly(this, config)
    }
    
    private suspend fun startLoggingSession() {
        val sessionId = "experiment_${System.currentTimeMillis()}"
        val sessionDir = File(filesDir, "sessions/$sessionId")
        
        try {
            // Start logging mode (initializes connection management)
            shimmerRecorder.start(sessionDir)
            
            // Connect to specific Shimmer device for remote control
            val deviceAddress = "00:06:66:12:34:56" 
            val deviceName = "Shimmer3-GSR-Lab"
            
            val connected = shimmerRecorder.connectToDevice(deviceAddress, deviceName)
            
            if (connected) {
                Log.i("Example", "Logging started on Shimmer SD card")
                monitorLoggingSession()
            } else {
                Log.e("Example", "Failed to connect to Shimmer device")
                throw IllegalStateException("Device connection required for logging mode")
            }
            
        } catch (e: Exception) {
            Log.e("Example", "Failed to start logging session", e)
        }
    }
    
    private fun monitorLoggingSession() {
        lifecycleScope.launch {
            while (shimmerRecorder.getRecordingStats().isRecording) {
                val stats = shimmerRecorder.getRecordingStats()
                val loggingStatus = stats.loggingStatus
                
                if (loggingStatus != null) {
                    Log.d("Example", 
                        "Logging: ${loggingStatus.isLogging}, " +
                        "Duration: ${loggingStatus.loggingDuration}ms, " +
                        "Device: ${loggingStatus.deviceName}")
                }
                
                delay(2000) // Check every 2 seconds
            }
        }
    }
}
```

### Advanced Logging Configuration
```kotlin
class AdvancedLoggingExample : AppCompatActivity() {
    
    private fun createHighFrequencyLoggingConfig(): ShimmerLoggingConfig {
        return ShimmerLoggingConfig(
            samplingRate = 256.0,      // High-frequency sampling
            gsrRange = 1,              // Medium sensitivity range
            enablePPG = true,          // Include PPG for heart rate
            enableAccel = true,        // Include accelerometer for movement
            sessionDurationMinutes = 120 // 2-hour session
        )
    }
    
    private fun createLowPowerLoggingConfig(): ShimmerLoggingConfig {
        return ShimmerLoggingConfig(
            samplingRate = 64.0,       // Lower sampling rate
            gsrRange = 0,              // High sensitivity
            enablePPG = false,         // Disable PPG to save power
            enableAccel = false,       // Disable accelerometer
            sessionDurationMinutes = 480 // 8-hour session
        )
    }
}
```

## Multi-Modal Session Examples

### Coordinated Multi-Sensor Recording
```kotlin
class MultiModalRecordingExample : AppCompatActivity() {
    private lateinit var recordingController: RecordingController
    private lateinit var shimmerRecorder: ShimmerRecorder
    
    private fun setupMultiModalSession() {
        // Create session directory
        val sessionId = "multimodal_${System.currentTimeMillis()}"
        val sessionDir = File(filesDir, "sessions/$sessionId")
        
        // Create recorders for different sensors
        val rgbRecorder = RgbCameraRecorder(this)
        val thermalRecorder = ThermalCameraRecorder(this)
        
        // Create Shimmer recorder (can be either mode)
        shimmerRecorder = when (getPreferredShimmerMode()) {
            "streaming" -> ShimmerRecorder.forRealTimeStreaming(this)
            "logging" -> ShimmerRecorder.forLoggingOnly(this, createLoggingConfig())
            else -> ShimmerRecorder.forRealTimeStreaming(this)
        }
        
        // Setup recording controller with all sensors
        recordingController = RecordingController(this).apply {
            addRecorder("rgb", rgbRecorder)
            addRecorder("thermal", thermalRecorder)  
            addRecorder("gsr", shimmerRecorder)
        }
    }
    
    private suspend fun startMultiModalSession() {
        try {
            // Start coordinated recording of all sensors
            recordingController.startSession("multimodal_session")
            
            // If using Shimmer logging mode, connect to device
            if (shimmerRecorder.getRecordingMode() == ShimmerRecordingMode.LOGGING_ONLY) {
                connectShimmerDevice()
            }
            
            Log.i("Example", "Multi-modal session started successfully")
            
        } catch (e: Exception) {
            Log.e("Example", "Failed to start multi-modal session", e)
        }
    }
    
    private suspend fun connectShimmerDevice() {
        // Device selection logic here
        val deviceAddress = scanForShimmerDevices() 
        if (deviceAddress != null) {
            shimmerRecorder.connectToDevice(deviceAddress, "Shimmer3-Lab")
        }
    }
}
```

## Error Handling Examples

### Robust Error Handling
```kotlin
class RobustShimmerUsage : AppCompatActivity() {
    
    private suspend fun startSessionWithErrorHandling() {
        try {
            val recorder = ShimmerRecorder.forLoggingOnly(this)
            val sessionDir = File(filesDir, "sessions/robust_test")
            
            // Start with comprehensive error checking
            recorder.start(sessionDir)
            
            // Attempt device connection with retry logic
            val connected = connectWithRetry(recorder, maxRetries = 3)
            
            if (!connected) {
                handleConnectionFailure(recorder)
                return
            }
            
            // Monitor session for problems
            monitorSessionHealth(recorder)
            
        } catch (e: IllegalStateException) {
            Log.e("Example", "Bluetooth permissions required", e)
            handlePermissionError()
            
        } catch (e: Exception) {
            Log.e("Example", "Unexpected error during session", e)
            handleGeneralError(e)
        }
    }
    
    private suspend fun connectWithRetry(
        recorder: ShimmerRecorder, 
        maxRetries: Int
    ): Boolean {
        repeat(maxRetries) { attempt ->
            try {
                Log.i("Example", "Connection attempt ${attempt + 1}/$maxRetries")
                
                val deviceAddress = "00:06:66:12:34:56"
                val success = recorder.connectToDevice(deviceAddress, "Shimmer3-Retry")
                
                if (success) {
                    Log.i("Example", "Connected successfully on attempt ${attempt + 1}")
                    return true
                }
                
                // Wait before retry
                delay(2000 * (attempt + 1)) // Exponential backoff
                
            } catch (e: Exception) {
                Log.w("Example", "Connection attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        
        return false
    }
    
    private fun handleConnectionFailure(recorder: ShimmerRecorder) {
        when (recorder.getRecordingMode()) {
            ShimmerRecordingMode.REAL_TIME_STREAMING -> {
                Log.i("Example", "Continuing with simulation mode")
                // Streaming mode continues with simulation
            }
            ShimmerRecordingMode.LOGGING_ONLY -> {
                Log.e("Example", "Logging mode requires device connection")
                // Must stop logging mode
                lifecycleScope.launch { recorder.stop() }
            }
        }
    }
}
```

## Session Data Analysis Examples

### Processing Session Results
```kotlin
class SessionAnalysisExample {
    
    fun analyzeStreamingSession(sessionDir: File) {
        val gsrFile = File(sessionDir, "gsr_data.csv")
        
        if (gsrFile.exists()) {
            Log.i("Analysis", "GSR data available at: ${gsrFile.absolutePath}")
            
            // Read CSV data for analysis
            val gsrData = parseGSRData(gsrFile)
            Log.i("Analysis", "Processed ${gsrData.size} GSR samples")
            
            // Perform statistical analysis
            val stats = calculateGSRStatistics(gsrData)
            Log.i("Analysis", "GSR Stats: Mean=${stats.mean}µS, StdDev=${stats.stdDev}µS")
        }
    }
    
    fun analyzeLoggingSession(sessionDir: File) {
        val metadataFile = File(sessionDir, "shimmer_logging_metadata.json")
        
        if (metadataFile.exists()) {
            val metadata = JSONObject(metadataFile.readText())
            
            Log.i("Analysis", "Logging session metadata:")
            Log.i("Analysis", "  Duration: ${metadata.getLong("duration_ms")}ms") 
            Log.i("Analysis", "  Device: ${metadata.getJSONObject("device_info").getString("device_name")}")
            Log.i("Analysis", "  Sampling Rate: ${metadata.getJSONObject("device_info").getDouble("sampling_rate_hz")}Hz")
            Log.i("Analysis", "  Data Location: ${metadata.getString("data_location")}")
            
            // Note: Actual data retrieval requires connecting to Shimmer device
            Log.i("Analysis", "Use Consensys software to download data from Shimmer SD card")
        }
    }
}
```

## Configuration Management Examples

### Dynamic Mode Selection
```kotlin
class ConfigurableShimmerExample : AppCompatActivity() {
    
    enum class SessionType {
        QUICK_TEST,      // Short streaming session
        LAB_EXPERIMENT,  // Long logging session  
        FIELD_STUDY,     // Portable logging session
        DEBUG_SESSION    // Streaming with detailed logging
    }
    
    fun createRecorderForSession(sessionType: SessionType): ShimmerRecorder {
        return when (sessionType) {
            SessionType.QUICK_TEST -> {
                ShimmerRecorder.forRealTimeStreaming(this)
            }
            
            SessionType.LAB_EXPERIMENT -> {
                val config = ShimmerLoggingConfig(
                    samplingRate = 256.0,
                    gsrRange = 0,
                    enablePPG = true,
                    enableAccel = true,
                    sessionDurationMinutes = 180 // 3 hours
                )
                ShimmerRecorder.forLoggingOnly(this, config)
            }
            
            SessionType.FIELD_STUDY -> {
                val config = ShimmerLoggingConfig(
                    samplingRate = 128.0,
                    gsrRange = 1, 
                    enablePPG = false, // Save power
                    enableAccel = false,
                    sessionDurationMinutes = 480 // 8 hours
                )
                ShimmerRecorder.forLoggingOnly(this, config)
            }
            
            SessionType.DEBUG_SESSION -> {
                ShimmerRecorder.forRealTimeStreaming(this)
            }
        }
    }
}
```

These examples demonstrate the flexibility and robustness of the dual-mode Shimmer integration, supporting both real-time data streaming and SD card logging scenarios with comprehensive error handling and configuration options.