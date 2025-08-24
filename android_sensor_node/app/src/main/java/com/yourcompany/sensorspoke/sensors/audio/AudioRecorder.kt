package com.yourcompany.sensorspoke.sensors.audio

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

/**
 * Audio recorder implementation for FR5 requirement.
 * 
 * Records audio using Android's MediaRecorder at 44.1 kHz stereo
 * as specified in the requirements documentation.
 */
class AudioRecorder(
    private val context: Context
) : SensorRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var sessionDir: File? = null
    private var isRecording = false
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 44100  // 44.1 kHz as per FR5
        private const val CHANNELS = 2          // Stereo
        private const val BIT_RATE = 128000     // 128 kbps AAC
    }

    override suspend fun start(sessionDir: File) {
        this.sessionDir = sessionDir
        try {
            initializeAudioRecorder()
            startRecording()
            Log.d(TAG, "Audio recording started: ${audioFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}", e)
            // Don't throw - allow session to continue without audio if needed
            startSimulationMode()
        }
    }

    override suspend fun stop() {
        try {
            stopRecording()
            Log.d(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording: ${e.message}", e)
        } finally {
            cleanup()
        }
    }

    private fun initializeAudioRecorder() {
        // Create audio file
        audioFile = File(sessionDir!!, "audio_${System.currentTimeMillis()}.m4a")
        
        // Initialize MediaRecorder
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFile!!.absolutePath)
            
            // Configure for high quality stereo recording
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioEncodingBitRate(BIT_RATE)
            setAudioChannels(CHANNELS)
            
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare failed", e)
                throw RuntimeException("Failed to prepare audio recorder", e)
            }
        }
    }

    private fun startRecording() {
        mediaRecorder?.let { recorder ->
            try {
                recorder.start()
                isRecording = true
                
                // Log recording start event with timestamp
                logAudioEvent("RECORDING_STARTED", System.nanoTime())
                
            } catch (e: RuntimeException) {
                Log.e(TAG, "MediaRecorder start failed", e)
                throw RuntimeException("Failed to start audio recording", e)
            }
        } ?: throw IllegalStateException("MediaRecorder not initialized")
    }

    private fun stopRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.let { recorder ->
                    recorder.stop()
                    isRecording = false
                    
                    // Log recording stop event with timestamp
                    logAudioEvent("RECORDING_STOPPED", System.nanoTime())
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "MediaRecorder stop failed", e)
                // Continue cleanup even if stop fails
            }
        }
    }

    private fun cleanup() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder", e)
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }

    private fun logAudioEvent(event: String, timestampNs: Long) {
        try {
            val eventFile = File(sessionDir!!, "audio_events.csv")
            
            if (!eventFile.exists()) {
                eventFile.writeText("timestamp_ns,event,audio_file\n")
            }
            
            val audioFileName = audioFile?.name ?: "unknown"
            eventFile.appendText("$timestampNs,$event,$audioFileName\n")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log audio event", e)
        }
    }

    private fun startSimulationMode() {
        Log.w(TAG, "Starting audio simulation mode - no real recording")
        
        // Create a placeholder file to indicate audio was attempted
        coroutineScope.launch {
            try {
                val placeholderFile = File(sessionDir!!, "audio_simulation.txt")
                placeholderFile.writeText(
                    "Audio recording simulation mode\n" +
                    "Started at: ${System.currentTimeMillis()}\n" +
                    "Reason: Real audio recording failed or not available\n"
                )
                
                // Log simulation events
                logAudioEvent("SIMULATION_STARTED", System.nanoTime())
                
                // Simulate recording for logging purposes
                delay(100)  // Small delay to simulate initialization
                
                logAudioEvent("SIMULATION_ACTIVE", System.nanoTime())
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create audio simulation", e)
            }
        }
    }
}