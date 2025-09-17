package com.yourcompany.sensorspoke.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.sensorspoke.controller.SessionOrchestrator
import com.yourcompany.sensorspoke.ui.models.MainUiState
import com.yourcompany.sensorspoke.ui.models.SensorStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Enhanced MainViewModel with comprehensive UI feedback and sensor status tracking.
 * Coordinates with RecordingController and provides real-time status updates for all sensors.
 *
 * This ViewModel implements the UI feedback requirements including:
 * - Real-time sensor status indicators
 * - Recording timer and status updates
 * - Error handling and notifications
 * - Thermal camera simulation feedback
 */
class MainViewModel : ViewModel() {

    // Comprehensive UI state
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // RecordingController coordination - using SessionOrchestrator interface
    private var sessionOrchestrator: SessionOrchestrator? = null
    
    // Recording timer job
    private var recordingTimerJob: Job? = null

    /**
     * Initialize the ViewModel with a SessionOrchestrator instance (typically RecordingController)
     */
    fun initialize(orchestrator: SessionOrchestrator) {
        sessionOrchestrator = orchestrator

        // Initialize UI state
        updateUiState { it.copy(isInitialized = true, statusText = "Ready to connect") }

        // Observe orchestrator state changes
        viewModelScope.launch {
            orchestrator.state.collect { orchestratorState ->
                when (orchestratorState) {
                    SessionOrchestrator.State.IDLE -> {
                        stopRecordingTimer()
                        updateUiState { 
                            it.copy(
                                isRecording = false,
                                startButtonEnabled = true,
                                stopButtonEnabled = false,
                                statusText = "Ready to record"
                            )
                        }
                    }
                    SessionOrchestrator.State.PREPARING -> {
                        updateUiState { 
                            it.copy(
                                isRecording = false,
                                startButtonEnabled = false,
                                stopButtonEnabled = false,
                                statusText = "Preparing to record..."
                            )
                        }
                    }
                    SessionOrchestrator.State.RECORDING -> {
                        startRecordingTimer()
                        updateUiState { 
                            it.copy(
                                isRecording = true,
                                startButtonEnabled = false,
                                stopButtonEnabled = true,
                                statusText = "Recording in progress..."
                            )
                        }
                    }
                    SessionOrchestrator.State.STOPPING -> {
                        updateUiState { 
                            it.copy(
                                startButtonEnabled = false,
                                stopButtonEnabled = false,
                                statusText = "Stopping recording..."
                            )
                        }
                    }
                }
            }
        }

        // Observe current session
        viewModelScope.launch {
            orchestrator.currentSessionId.collect { sessionId ->
                updateUiState { it.copy(currentSessionId = sessionId) }
            }
        }

        // If orchestrator is RecordingController, observe additional features
        if (orchestrator is com.yourcompany.sensorspoke.controller.RecordingController) {
            // Observe sensor states
            viewModelScope.launch {
                orchestrator.sensorStates.collect { sensorStates ->
                    val statusMap = sensorStates.mapValues { (sensorName, state) ->
                        val isActive = state == com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.RECORDING
                        val isHealthy = state != com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.ERROR
                        val statusMessage = when (state) {
                            com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.IDLE -> "Ready"
                            com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.STARTING -> "Starting..."
                            com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.RECORDING -> "Recording"
                            com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.STOPPING -> "Stopping..."
                            com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.STOPPED -> "Stopped"
                            com.yourcompany.sensorspoke.controller.RecordingController.RecorderState.ERROR -> "Error"
                        }
                        SensorStatus(sensorName, isActive, isHealthy, System.currentTimeMillis(), statusMessage)
                    }
                    _sensorStatus.value = statusMap
                }
            }

            // Observe session start results for partial failure notifications
            viewModelScope.launch {
                orchestrator.lastSessionResult.collect { result ->
                    result?.let {
                        if (it.isPartialSuccess) {
                            val failedSensors = it.failedSensors.keys.joinToString(", ")
                            _statusMessage.value = "Recording started (${failedSensors} failed)"
                        }
                    }
                }
            }
        }
    }

    /**
     * Start a new recording session
     */
    fun startRecording(sessionId: String? = null) {
        viewModelScope.launch {
            try {
                clearError()
                updateUiState { it.copy(statusText = "Starting recording...") }
                sessionOrchestrator?.startSession(sessionId)
            } catch (e: Exception) {
                showError("Failed to start recording: ${e.message}")
            }
        }
    }

    /**
     * Stop the current recording session
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                clearError()
                updateUiState { it.copy(statusText = "Stopping recording...") }
                sessionOrchestrator?.stopSession()
            } catch (e: Exception) {
                showError("Failed to stop recording: ${e.message}")
            }
        }
    }

    /**
     * Update sensor status for UI display
     */
    fun updateSensorStatus(sensorName: String, status: SensorStatus) {
        when (sensorName.lowercase()) {
            "rgb", "camera", "rgb_camera" -> {
                updateUiState { 
                    it.copy(
                        isCameraConnected = status.isActive,
                        cameraStatus = status
                    )
                }
            }
            "thermal", "thermal_camera" -> {
                updateUiState { 
                    it.copy(
                        isThermalConnected = status.isActive,
                        thermalStatus = status,
                        thermalIsSimulated = status.statusMessage.contains("Simulated", ignoreCase = true)
                    )
                }
            }
            "gsr", "shimmer" -> {
                updateUiState { 
                    it.copy(
                        isShimmerConnected = status.isActive,
                        shimmerStatus = status
                    )
                }
            }
            "pc", "pc_link", "hub" -> {
                updateUiState { 
                    it.copy(
                        isPcConnected = status.isActive,
                        pcStatus = status
                    )
                }
            }
        }
        
        // Update overall button states based on sensor connectivity
        updateButtonStates()
    }

    /**
     * Show error message with dialog
     */
    fun showError(message: String) {
        updateUiState { 
            it.copy(
                errorMessage = message,
                showErrorDialog = true,
                statusText = "Error: $message"
            )
        }
    }

    /**
     * Clear error message and hide dialog
     */
    fun clearError() {
        updateUiState { 
            it.copy(
                errorMessage = null,
                showErrorDialog = false
            )
        }
    }

    /**
     * Show progress with message
     */
    fun showProgress(message: String) {
        updateUiState { 
            it.copy(
                showProgress = true,
                progressMessage = message
            )
        }
    }

    /**
     * Hide progress
     */
    fun hideProgress() {
        updateUiState { 
            it.copy(showProgress = false, progressMessage = "")
        }
    }

    /**
     * Update RGB preview availability
     */
    fun updateRgbPreviewAvailable(available: Boolean) {
        updateUiState { it.copy(rgbPreviewAvailable = available) }
    }

    /**
     * Update thermal preview availability
     */
    fun updateThermalPreviewAvailable(available: Boolean) {
        updateUiState { it.copy(thermalPreviewAvailable = available) }
    }

    /**
     * Update status text
     */
    fun updateStatusText(text: String) {
        updateUiState { it.copy(statusText = text) }
    }

    private fun updateUiState(update: (MainUiState) -> MainUiState) {
        _uiState.value = update(_uiState.value)
    }

    private fun updateButtonStates() {
        val currentState = _uiState.value
        val canStartRecording = currentState.isInitialized && 
                               !currentState.isRecording && 
                               (currentState.isCameraConnected || currentState.isThermalConnected || currentState.isShimmerConnected)
        
        updateUiState { 
            it.copy(
                startButtonEnabled = canStartRecording,
                stopButtonEnabled = currentState.isRecording
            )
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            var seconds = 0L
            while (true) {
                updateUiState { it.copy(recordingDurationSeconds = seconds) }
                delay(1000)
                seconds++
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        updateUiState { it.copy(recordingDurationSeconds = 0) }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up any resources if needed
    }
}
