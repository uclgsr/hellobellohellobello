package com.yourcompany.sensorspoke.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.service.RecordingService
import kotlinx.coroutines.launch

/**
 * Phase 1 MainActivity - Simplified implementation focused on foundational architecture.
 * 
 * This activity provides:
 * - Basic Start/Stop recording functionality
 * - Service connection and NSD advertising
 * - BroadcastReceiver for remote commands from PC Hub
 * - Simple status display
 * - Session directory creation testing
 */
class Phase1MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "Phase1MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()
    private var recordingController: RecordingController? = null

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // BroadcastReceiver for commands from RecordingService
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_START_RECORDING -> {
                    val sessionId = intent.getStringExtra(RecordingService.EXTRA_SESSION_ID)
                    Log.i(TAG, "Received start recording command with sessionId: $sessionId")
                    updateStatus("PC Hub requested recording start")
                    lifecycleScope.launch {
                        try {
                            getRecordingController().startSession(sessionId)
                            updateStatus("Recording started by PC Hub")
                            updateButtonStates(isRecording = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start recording from PC Hub", e)
                            updateStatus("Error: Failed to start recording")
                        }
                    }
                }
                RecordingService.ACTION_STOP_RECORDING -> {
                    Log.i(TAG, "Received stop recording command")
                    updateStatus("PC Hub requested recording stop")
                    lifecycleScope.launch {
                        try {
                            recordingController?.stopSession()
                            updateStatus("Recording stopped by PC Hub")
                            updateButtonStates(isRecording = false)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to stop recording from PC Hub", e)
                            updateStatus("Error: Failed to stop recording")
                        }
                    }
                }
                RecordingService.ACTION_FLASH_SYNC -> {
                    val timestamp = intent.getLongExtra(RecordingService.EXTRA_FLASH_TS_NS, 0L)
                    Log.i(TAG, "Received flash sync command with timestamp: $timestamp")
                    // Flash sync implementation would go here
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phase1_main)
        
        // Initialize UI elements
        initializeViews()
        
        // Set up button click handlers
        setupButtonHandlers()
        
        // Start the RecordingService (foreground service with NSD)
        startRecordingService()
        
        updateStatus("Phase 1 initialized - Service starting...")
        Log.i(TAG, "Phase 1 MainActivity created")
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.btnStartRecording) 
        stopButton = findViewById(R.id.btnStopRecording)
        
        // Initial button states
        updateButtonStates(isRecording = false)
    }

    private fun setupButtonHandlers() {
        startButton.setOnClickListener {
            Log.i(TAG, "Manual start recording button clicked")
            updateStatus("Starting recording...")
            lifecycleScope.launch {
                try {
                    getRecordingController().startSession()
                    updateStatus("Recording started manually")
                    updateButtonStates(isRecording = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording manually", e)
                    updateStatus("Error: Failed to start recording")
                }
            }
        }

        stopButton.setOnClickListener {
            Log.i(TAG, "Manual stop recording button clicked")
            updateStatus("Stopping recording...")
            lifecycleScope.launch {
                try {
                    recordingController?.stopSession()
                    updateStatus("Recording stopped manually")
                    updateButtonStates(isRecording = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop recording manually", e)
                    updateStatus("Error: Failed to stop recording")
                }
            }
        }
    }

    private fun startRecordingService() {
        val serviceIntent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.i(TAG, "RecordingService start requested")
    }

    private fun getRecordingController(): RecordingController {
        return recordingController ?: run {
            val controller = RecordingController(applicationContext)
            
            // For Phase 1, register stub sensor recorders for testing
            controller.register("stub_sensor", StubSensorRecorder())
            
            recordingController = controller
            Log.i(TAG, "RecordingController initialized with stub sensors")
            controller
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
        Log.i(TAG, "Status: $message")
    }

    private fun updateButtonStates(isRecording: Boolean) {
        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording
        
        if (isRecording) {
            startButton.text = "Recording..."
            stopButton.text = "Stop Recording"
        } else {
            startButton.text = "Start Recording"
            stopButton.text = "Stop Recording"
        }
    }

    override fun onStart() {
        super.onStart()
        // Register broadcast receiver for commands from RecordingService
        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_START_RECORDING)
            addAction(RecordingService.ACTION_STOP_RECORDING)
            addAction(RecordingService.ACTION_FLASH_SYNC)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(commandReceiver, filter)
        }
        
        updateStatus("Ready - Service advertising on network")
        Log.i(TAG, "Phase 1 MainActivity started and listening for PC Hub commands")
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister broadcast receiver", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up recording controller
        lifecycleScope.launch {
            try {
                recordingController?.stopSession()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session on destroy", e)
            }
        }
        Log.i(TAG, "Phase 1 MainActivity destroyed")
    }
}