package com.yourcompany.sensorspoke.sensors.gsr

import android.content.Context
import android.util.Log
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

/**
 * Simplified GSR recorder for compilation - focuses on data management improvements
 * This is a minimal working version while the full Shimmer integration is refined
 */
class ShimmerRecorder(
    private val context: Context,
) : SensorRecorder {
    companion object {
        private const val TAG = "ShimmerRecorder"
        private const val SAMPLING_RATE_HZ = 128.0
        private const val SAMPLE_INTERVAL_MS = 7L // ~128Hz
    }

    private var isRecording = false
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var dataPointCount = 0

    override suspend fun start(sessionDir: File) {
        Log.i(TAG, "Starting GSR recording in session: ${sessionDir.absolutePath}")

        // Create CSV file for GSR data with proper format
        csvFile = File(sessionDir, "gsr.csv")
        csvWriter = BufferedWriter(FileWriter(csvFile!!))

        // Write CSV header with enhanced format for data management
        csvWriter!!.write("timestamp_ns,timestamp_ms,sample_number,gsr_kohms,gsr_raw_12bit,ppg_raw,connection_status,data_integrity\n")
        csvWriter!!.flush()

        isRecording = true
        startSimulatedRecording()

        Log.i(TAG, "GSR recording started successfully")
    }

    override suspend fun stop() {
        Log.i(TAG, "Stopping GSR recording")
        
        isRecording = false
        
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

        // Cancel scope
        scope.cancel()
        
        Log.i(TAG, "GSR recording stopped, ${dataPointCount} samples recorded")
    }
    private fun startSimulatedRecording() {

        recordingJob = scope.launch {
            try {
                while (isActive && isRecording) {
                    val timestampNs = System.nanoTime()
                    val timestampMs = System.currentTimeMillis()
                    
                    // Simulate realistic GSR data with proper 12-bit range
                    val gsrRaw = Random.nextInt(512, 3584) // 12-bit ADC range subset
                    val gsrKohms = (gsrRaw / 4095.0) * 1000.0 // Convert to kOhms
                    
                    // Simulate PPG data
                    val ppgRaw = Random.nextInt(1500, 2500)
                    
                    // Enhanced data integrity tracking
                    val connectionStatus = "SIMULATED"
                    val dataIntegrity = "OK"
                    
                    // Write to CSV with enhanced format for data management
                    csvWriter?.apply {
                        write("$timestampNs,$timestampMs,$dataPointCount,${"%.3f".format(gsrKohms)},$gsrRaw,$ppgRaw,$connectionStatus,$dataIntegrity\n")
                        
                        // Flush every 50 samples for data integrity (as mentioned in problem statement)
                        if (dataPointCount % 50 == 0) {
                            flush()
                        }
                    }
                    
                    dataPointCount++
                    
                    // Log progress periodically
                    if (dataPointCount % 128 == 0) {
                        Log.d(TAG, "GSR sample $dataPointCount: ${"%.2f".format(gsrKohms)} kΩ (raw: $gsrRaw), PPG: $ppgRaw")
                    }
                    
                    delay(SAMPLE_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in GSR recording: ${e.message}", e)
            } finally {
                // Final flush on completion
                csvWriter?.flush()
            }
        }
    }
}
