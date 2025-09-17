package com.yourcompany.sensorspoke.sensors.gsr

import android.content.Context
import android.util.Log
import com.yourcompany.sensorspoke.network.DeviceConnectionManager
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

/**
 * Refactored ShimmerRecorder focused on data logging with improved separation of concerns.
 * Device management is now handled by ShimmerManager, data processing by ShimmerDataProcessor.
 * 
 * This recorder is responsible for:
 * - Managing CSV file output for GSR data
 * - Coordinating with ShimmerManager for device state
 * - Reporting status via Flow for UI reactivity
 * - Session-based recording lifecycle
 */
class ShimmerRecorder(
    private val context: Context,
    private val shimmerManager: ShimmerManager? = null,
    private val deviceConnectionManager: DeviceConnectionManager? = null
) : SensorRecorder {
    companion object {
        private const val TAG = "ShimmerRecorder"
        private const val SAMPLING_RATE_HZ = 128.0
        private const val SAMPLE_INTERVAL_MS = 7L // ~128Hz
    }

    // Data processing utility
    private val dataProcessor = ShimmerDataProcessor()

    // Recording state
    private var isRecording = false
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var dataPointCount = 0

    // Status reporting for UI reactivity
    private val _recordingStatus = MutableStateFlow(RecordingStatus.IDLE)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

    private val _dataRate = MutableStateFlow(0.0)
    val dataRate: StateFlow<Double> = _dataRate.asStateFlow()

    /**
     * Recording status enum for UI state management
     */
    enum class RecordingStatus {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
        ERROR
    }

    override suspend fun start(sessionDir: File) {
        Log.i(TAG, "Starting GSR recording in session: ${sessionDir.absolutePath}")
        _recordingStatus.value = RecordingStatus.STARTING

        try {
            // Initialize ShimmerManager if available
            shimmerManager?.let { manager ->
                if (!manager.initialize()) {
                    throw RuntimeException("Failed to initialize ShimmerManager")
                }
                
                // Update device connection manager
                deviceConnectionManager?.updateShimmerState(
                    DeviceConnectionManager.DeviceState.CONNECTING,
                    DeviceConnectionManager.DeviceDetails(
                        deviceType = "Shimmer3 GSR+",
                        deviceName = "GSR Sensor",
                        connectionState = DeviceConnectionManager.DeviceState.CONNECTING,
                        isRequired = true
                    )
                )
            }

            // Create CSV file for GSR data
            csvFile = File(sessionDir, "gsr.csv")
            csvWriter = BufferedWriter(FileWriter(csvFile!!))

            // Use data processor for consistent header format
            csvWriter!!.write(dataProcessor.getCsvHeader() + "\n")
            csvWriter!!.flush()

            isRecording = true
            startRecordingLoop()

            _recordingStatus.value = RecordingStatus.RECORDING
            
            // Update device connection state to connected
            deviceConnectionManager?.updateShimmerState(
                DeviceConnectionManager.DeviceState.CONNECTED,
                DeviceConnectionManager.DeviceDetails(
                    deviceType = "Shimmer3 GSR+",
                    deviceName = "GSR Sensor",
                    connectionState = DeviceConnectionManager.DeviceState.CONNECTED,
                    dataRate = SAMPLING_RATE_HZ,
                    isRequired = true
                )
            )

            Log.i(TAG, "GSR recording started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GSR recording: ${e.message}", e)
            _recordingStatus.value = RecordingStatus.ERROR
            
            deviceConnectionManager?.updateShimmerState(
                DeviceConnectionManager.DeviceState.ERROR,
                DeviceConnectionManager.DeviceDetails(
                    deviceType = "Shimmer3 GSR+",
                    deviceName = "GSR Sensor",
                    connectionState = DeviceConnectionManager.DeviceState.ERROR,
                    errorMessage = e.message,
                    isRequired = true
                )
            )
            throw e
        }
    }

    override suspend fun stop() {
        Log.i(TAG, "Stopping GSR recording")
        _recordingStatus.value = RecordingStatus.STOPPING
        
        isRecording = false
        
        try {
            // Stop recording job and wait for completion
            recordingJob?.apply {
                cancel()
                join() // Ensure proper cleanup
            }
            recordingJob = null

            // Flush and close CSV resources with proper error handling
            csvWriter?.apply {
                try {
                    flush() // Ensure all data is written
                    close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing CSV writer: ${e.message}", e)
                }
            }
            csvWriter = null
            csvFile = null

            // Clean up ShimmerManager
            shimmerManager?.cleanup()

            // Update device connection state
            deviceConnectionManager?.updateShimmerState(DeviceConnectionManager.DeviceState.DISCONNECTED)

            // Cancel scope
            scope.cancel()
            
            _recordingStatus.value = RecordingStatus.IDLE
            _dataRate.value = 0.0
            
            Log.i(TAG, "GSR recording stopped, ${dataPointCount} samples recorded")
        } catch (e: Exception) {
            Log.e(TAG, "Error during GSR recording stop: ${e.message}", e)
            _recordingStatus.value = RecordingStatus.ERROR
            throw e
        }
    }
    /**
     * Start the recording loop - renamed for clarity
     */
    private fun startRecordingLoop() {
        recordingJob = scope.launch {
            try {
                Log.d(TAG, "Starting GSR data recording loop")
                
                while (isActive && isRecording) {
                    // Generate simulated sensor sample using data processor format
                    val sample = generateSimulatedSample()
                    
                    // Use data processor for consistent CSV formatting
                    csvWriter?.apply {
                        write(dataProcessor.formatSampleForCsv(sample, dataPointCount) + "\n")
                        
                        // Flush every 50 samples for data integrity
                        if (dataPointCount % 50 == 0) {
                            flush()
                        }
                    }
                    
                    dataPointCount++
                    
                    // Update data rate for UI
                    if (dataPointCount % 128 == 0) {
                        val currentRate = calculateCurrentDataRate()
                        _dataRate.value = currentRate
                        
                        Log.d(TAG, "GSR sample $dataPointCount: ${"%.2f".format(sample.gsrKohms)} kΩ (raw: ${sample.gsrRaw12bit}), Rate: ${"%.1f".format(currentRate)} Hz")
                    }
                    
                    delay(SAMPLE_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in GSR recording loop: ${e.message}", e)
                _recordingStatus.value = RecordingStatus.ERROR
            } finally {
                // Final flush on completion
                csvWriter?.flush()
            }
        }
    }

    /**
     * Generate simulated sensor sample using data processor format
     */
    private fun generateSimulatedSample(): ShimmerDataProcessor.SensorSample {
        val timestampNs = System.nanoTime()
        val timestampMs = System.currentTimeMillis()
        
        // Simulate realistic GSR data with proper 12-bit range
        val gsrRaw = Random.nextInt(512, 3584) // 12-bit ADC range subset
        val gsrKohms = (gsrRaw / 4095.0) * 1000.0 // Convert to kOhms
        
        // Simulate PPG data
        val ppgRaw = Random.nextInt(1500, 2500)
        
        return ShimmerDataProcessor.SensorSample(
            timestampNs = timestampNs,
            timestampMs = timestampMs,
            gsrKohms = gsrKohms,
            gsrRaw12bit = gsrRaw,
            ppgRaw = ppgRaw,
            connectionStatus = "SIMULATED",
            dataIntegrity = "OK"
        )
    }

    /**
     * Calculate current data rate for monitoring
     */
    private fun calculateCurrentDataRate(): Double {
        return if (dataPointCount > 0) {
            SAMPLING_RATE_HZ // For simulation, return target rate
        } else {
            0.0
        }
    }

    /**
     * Get current recording statistics for external monitoring
     */
    fun getRecordingStatistics(): RecordingStatistics {
        return RecordingStatistics(
            isRecording = isRecording,
            samplesRecorded = dataPointCount,
            targetSamplingRate = SAMPLING_RATE_HZ,
            actualDataRate = _dataRate.value,
            recordingStatus = _recordingStatus.value
        )
    }

    /**
     * Recording statistics data class
     */
    data class RecordingStatistics(
        val isRecording: Boolean,
        val samplesRecorded: Int,
        val targetSamplingRate: Double,
        val actualDataRate: Double,
        val recordingStatus: RecordingStatus
    )
}
