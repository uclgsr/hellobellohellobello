package com.yourcompany.sensorspoke.controller

import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * SessionOrchestrator interface defines the contract for managing multi-sensor recording sessions.
 * 
 * This interface ensures clean separation between the session management logic and its implementation,
 * allowing for easy testing and potential alternative implementations while maintaining the same API.
 * 
 * Key responsibilities:
 * - Coordinate start/stop of multiple sensor recorders
 * - Manage session lifecycle with proper state transitions
 * - Provide session directory management
 * - Offer reactive state updates via StateFlow
 */
interface SessionOrchestrator {
    
    /**
     * Session states for tracking lifecycle
     */
    enum class State {
        IDLE,
        PREPARING,
        RECORDING, 
        STOPPING
    }
    
    /**
     * Current session state
     */
    val state: StateFlow<State>
    
    /**
     * Current session ID, null when not recording
     */
    val currentSessionId: StateFlow<String?>
    
    /**
     * Register a sensor recorder with the orchestrator
     * @param name Unique name for this recorder
     * @param recorder The SensorRecorder implementation
     */
    fun register(name: String, recorder: SensorRecorder)
    
    /**
     * Unregister a sensor recorder
     * @param name Name of the recorder to remove
     */
    fun unregister(name: String)
    
    /**
     * Start a new multi-sensor recording session
     * @param sessionId Optional session ID, generates new one if null
     * @throws IllegalStateException if already recording
     */
    suspend fun startSession(sessionId: String? = null)
    
    /**
     * Stop the current recording session
     * @throws IllegalStateException if not currently recording
     */
    suspend fun stopSession()
    
    /**
     * Get the current session directory
     * @return Session directory File, or null if not recording
     */
    fun getCurrentSessionDirectory(): File?
    
    /**
     * Get the root sessions directory where all sessions are stored
     */
    fun getSessionsRootDirectory(): File
    
    /**
     * Get list of registered sensor recorder names
     */
    fun getRegisteredSensors(): List<String>
}