package com.yourcompany.sensorspoke.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.sensorspoke.controller.SessionOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MainViewModel coordinates with RecordingController to reflect recording state via StateFlow.
 * Follows MVVM architecture with proper separation of UI logic from business logic.
 * 
 * This ViewModel acts as the UI layer's interface to the RecordingController session orchestrator,
 * providing reactive state updates for the UI while keeping business logic in the controller layer.
 */
class MainViewModel : ViewModel() {
    
    // Recording state management
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
    
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    // UI state management
    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Sensor status tracking
    private val _sensorStatus = MutableStateFlow<Map<String, SensorStatus>>(emptyMap())
    val sensorStatus: StateFlow<Map<String, SensorStatus>> = _sensorStatus.asStateFlow()
    
    // RecordingController coordination - using SessionOrchestrator interface
    private var sessionOrchestrator: SessionOrchestrator? = null
    
    /**
     * Recording states that mirror SessionOrchestrator states for UI reactivity
     */
    enum class RecordingState {
        IDLE,
        PREPARING,
        RECORDING,
        STOPPING,
        ERROR
    }
    
    /**
     * Individual sensor status for UI display
     */
    data class SensorStatus(
        val name: String,
        val isActive: Boolean,
        val isHealthy: Boolean,
        val lastUpdate: Long = System.currentTimeMillis(),
        val statusMessage: String = ""
    )
    
    /**
     * Initialize the ViewModel with a SessionOrchestrator instance (typically RecordingController)
     */
    fun initialize(orchestrator: SessionOrchestrator) {
        sessionOrchestrator = orchestrator
        
        // Observe orchestrator state changes
        viewModelScope.launch {
            orchestrator.state.collect { orchestratorState ->
                _recordingState.value = when (orchestratorState) {
                    SessionOrchestrator.State.IDLE -> RecordingState.IDLE
                    SessionOrchestrator.State.PREPARING -> RecordingState.PREPARING
                    SessionOrchestrator.State.RECORDING -> RecordingState.RECORDING
                    SessionOrchestrator.State.STOPPING -> RecordingState.STOPPING
                }
                _isRecording.value = orchestratorState == SessionOrchestrator.State.RECORDING
            }
        }
        
        // Observe current session
        viewModelScope.launch {
            orchestrator.currentSessionId.collect { sessionId ->
                _currentSessionId.value = sessionId
                _statusMessage.value = if (sessionId != null) {
                    "Session: $sessionId"
                } else {
                    "Ready"
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
                _errorMessage.value = null
                sessionOrchestrator?.startSession(sessionId)
            } catch (e: Exception) {
                _recordingState.value = RecordingState.ERROR
                _errorMessage.value = "Failed to start recording: ${e.message}"
            }
        }
    }
    
    /**
     * Stop the current recording session
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                _errorMessage.value = null
                sessionOrchestrator?.stopSession()
            } catch (e: Exception) {
                _recordingState.value = RecordingState.ERROR
                _errorMessage.value = "Failed to stop recording: ${e.message}"
            }
        }
    }
    
    /**
     * Update sensor status for UI display
     */
    fun updateSensorStatus(sensorName: String, status: SensorStatus) {
        val currentStatus = _sensorStatus.value.toMutableMap()
        currentStatus[sensorName] = status
        _sensorStatus.value = currentStatus
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up any resources if needed
    }
}
