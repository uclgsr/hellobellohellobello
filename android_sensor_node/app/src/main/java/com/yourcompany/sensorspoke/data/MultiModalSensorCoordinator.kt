package com.yourcompany.sensorspoke.data

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import com.yourcompany.sensorspoke.sensors.audio.AudioRecorder
import com.yourcompany.sensorspoke.sensors.gsr.ShimmerRecorder
import com.yourcompany.sensorspoke.sensors.rgb.RgbCameraRecorder
import com.yourcompany.sensorspoke.sensors.thermal.ThermalCameraRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Multi-Modal Sensor Coordinator
 */
class MultiModalSensorCoordinator(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "MultiModalCoordinator"
    }

    private val _systemStatus = MutableStateFlow("Initializing")
    val systemStatus: StateFlow<String> = _systemStatus
    
    private var recordingController: RecordingController? = null
    private var isInitialized = false

    suspend fun initializeSystem(): Boolean {
        return try {
            Log.i(TAG, "Initializing multi-modal sensor system")
            recordingController = RecordingController(context)
            
            val rgbRecorder = RgbCameraRecorder(context, lifecycleOwner)
            val thermalRecorder = ThermalCameraRecorder(context)
            val gsrRecorder = ShimmerRecorder(context)
            val audioRecorder = AudioRecorder(context)
            
            recordingController?.let { controller ->
                controller.register("rgb", rgbRecorder)
                controller.register("thermal", thermalRecorder)
                controller.register("gsr", gsrRecorder)
                controller.register("audio", audioRecorder)
            }
            
            isInitialized = true
            Log.i(TAG, "Multi-modal sensor system initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize sensor system: ${e.message}", e)
            false
        }
    }
    
    fun getRecordingController(): RecordingController? = recordingController
    
    fun isSystemReady(): Boolean = isInitialized
    
    fun cleanup() {
        Log.i(TAG, "Cleaning up multi-modal sensor coordinator")
        recordingController = null
        isInitialized = false
    }
}