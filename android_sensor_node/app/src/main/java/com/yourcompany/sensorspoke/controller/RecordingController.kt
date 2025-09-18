package com.yourcompany.sensorspoke.controller

import android.content.Context
import android.os.Build
import android.util.Log
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import com.yourcompany.sensorspoke.utils.SessionDataValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Enhanced RecordingController with crash recovery, health monitoring, and improved coordination.
 * 
 * Key enhancements implemented:
 * - Crash recovery integration with session restoration
 * - Real-time session health monitoring and error recovery
 * - Enhanced partial failure handling with automatic recovery attempts
 * - Improved sensor synchronization with precise timing coordination
 * - Session data validation and integrity checks
 * - Comprehensive error handling with graceful degradation
 * 
 * This is the central session orchestrator that:
 * - Holds each sensor's SensorRecorder implementation
 * - Starts and stops them together with synchronized timing
 * - Creates session directory and sub-folders per sensor
 * - Provides synchronized session management with common timestamp reference for multi-modal data alignment
 * - Handles partial failures gracefully (continues recording with available sensors)
 * - Performs prerequisite validation before starting sessions
 * - Provides recovery mechanisms for crashed or interrupted sessions
 * - Monitors session health in real-time with automatic error recovery
 */
class RecordingController(
    private val context: Context?,
    private val sessionsRootOverride: File? = null,
) : SessionOrchestrator {
    companion object {
        private const val TAG = "RecordingController"
        private const val HEALTH_CHECK_INTERVAL_MS = 5000L // 5 seconds
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_DELAY_MS = 2000L // 2 seconds between attempts
    }

    data class RecorderEntry(
        val name: String,
        val recorder: SensorRecorder,
        var state: RecorderState = RecorderState.IDLE,
        var isRequired: Boolean = false, // Whether this recorder is required for session to proceed
        var lastError: String? = null,
        var recoveryAttempts: Int = 0,
        var lastHealthCheck: Long = 0L,
    )

    /**
     * States that individual sensor recorders can be in
     */
    enum class RecorderState {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
        STOPPED,
        ERROR,
        RECOVERING, // New state for recovery attempts
    }

    /**
     * Enhanced prerequisites that must be validated before starting a session
     */
    data class SessionPrerequisites(
        val hasCameraPermission: Boolean,
        val hasStoragePermission: Boolean,
        val hasBluetoothPermission: Boolean,
        val availableStorageBytes: Long,
        val minimumRequiredBytes: Long = 100 * 1024 * 1024, // 100MB minimum
        val registeredSensors: List<String>,
        val crashRecoveryStatus: CrashRecoveryManager.RecoveryResult? = null,
    )

    /**
     * Result of a session start attempt, including partial failures
     */
    data class SessionStartResult(
        val sessionId: String,
        val successfulSensors: List<String>,
        val failedSensors: Map<String, String>, // sensor name -> error message
        val isPartialSuccess: Boolean,
        val hasRecoveredSensors: Boolean = false,
    )

    /**
     * Enhanced session health status
     */
    data class SessionHealthStatus(
        val isHealthy: Boolean,
        val activeSensors: Int,
        val erroredSensors: Int,
        val recoveringSensors: Int,
        val lastHealthCheck: Long,
        val sessionDurationMs: Long,
        val issues: List<String>,
    )

    // Use SessionOrchestrator.State for consistent interface
    private val _state = MutableStateFlow(SessionOrchestrator.State.IDLE)
    override val state: StateFlow<SessionOrchestrator.State> = _state

    private val _currentSessionId = MutableStateFlow<String?>(null)
    override val currentSessionId: StateFlow<String?> = _currentSessionId

    // Track individual sensor states for UI and partial failure handling
    private val _sensorStates = MutableStateFlow<Map<String, RecorderState>>(emptyMap())
    val sensorStates: StateFlow<Map<String, RecorderState>> = _sensorStates

    // Track last session start result for UI consumption
    private val _lastSessionResult = MutableStateFlow<SessionStartResult?>(null)
    val lastSessionResult: StateFlow<SessionStartResult?> = _lastSessionResult

    // Enhanced session health monitoring
    private val _sessionHealth = MutableStateFlow<SessionHealthStatus?>(null)
    val sessionHealth: StateFlow<SessionHealthStatus?> = _sessionHealth

    private val recorders = mutableListOf<RecorderEntry>()
    private var sessionRootDir: File? = null

    // Synchronized session timing for multi-modal alignment
    private var sessionStartTimestampNs: Long = 0L
    private var sessionStartTimestampMs: Long = 0L

    // Enhanced crash recovery and monitoring
    private var crashRecoveryManager: CrashRecoveryManager? = null
    private var healthMonitoringJob: Job? = null
    private var recoveryScope: CoroutineScope? = null

    init {
        // Initialize crash recovery manager
        context?.let {
            crashRecoveryManager = CrashRecoveryManager(it, sessionsRootOverride)
        }
        
        // Initialize recovery scope
        recoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    /**
     * Register a recorder under a unique name; its data will be stored under sessionDir/<name>.
     * @param name Unique name for this recorder
     * @param recorder The SensorRecorder implementation
     * @param isRequired Whether this sensor is required for session to proceed (default: false)
     */
    override fun register(
        name: String,
        recorder: SensorRecorder,
    ) {
        register(name, recorder, isRequired = false)
    }

    /**
     * Register a recorder with required flag
     */
    fun register(
        name: String,
        recorder: SensorRecorder,
        isRequired: Boolean,
    ) {
        require(recorders.none { it.name == name }) { "Recorder name '$name' already registered" }
        recorders.add(RecorderEntry(name, recorder, RecorderState.IDLE, isRequired))
        updateSensorStates()
    }

    /**
     * Unregister a recorder by name.
     */
    override fun unregister(name: String) {
        recorders.removeAll { it.name == name }
        updateSensorStates()
    }

    /**
     * Add a recorder (alias for register for backward compatibility).
     */
    fun addRecorder(name: String, recorder: SensorRecorder, isRequired: Boolean = false) {
        register(name, recorder, isRequired)
    }

    /**
     * Update the sensor states StateFlow
     */
    private fun updateSensorStates() {
        _sensorStates.value = recorders.associate { it.name to it.state }
    }

    /**
     * Enhanced startSession with crash recovery and improved coordination
     * Starts a new session with an optional provided sessionId. When null, a new id is generated.
     * Creates the session directory tree and starts all registered recorders with synchronized timing.
     * Handles partial failures gracefully - continues with available sensors if non-required sensors fail.
     */
    override suspend fun startSession(sessionId: String?) {
        if (_state.value != SessionOrchestrator.State.IDLE) {
            throw IllegalStateException("Cannot start session in state ${_state.value}")
        }

        _state.value = SessionOrchestrator.State.PREPARING

        try {
            // Step 1: Perform crash recovery before starting new session
            val recoveryResult = performCrashRecovery()
            Log.i(TAG, "Crash recovery completed: ${recoveryResult.crashedSessionsFound} crashed sessions found")

            // Step 2: Enhanced prerequisite validation with crash recovery status
            val prerequisites = validateRecordingPrerequisites(recoveryResult)
            if (!meetsMinimumRequirements(prerequisites)) {
                throw IllegalStateException("Prerequisites not met: ${getPrerequisiteErrors(prerequisites)}")
            }

            val id = sessionId ?: generateSessionId()
            val root = ensureSessionsRoot()
            val sessionDir = File(root, id)
            if (!sessionDir.exists()) sessionDir.mkdirs()

            // Step 3: Capture synchronized session start timestamps with enhanced precision
            sessionStartTimestampNs = System.nanoTime()
            sessionStartTimestampMs = System.currentTimeMillis()

            Log.i(TAG, "Starting enhanced session: $id with ${recorders.size} recorders")
            Log.i(TAG, "Session start timestamp: ${sessionStartTimestampMs}ms / ${sessionStartTimestampNs}ns")
            Log.i(TAG, "Recovery status: ${recoveryResult.crashedSessionsFound} previous crashes recovered")

            // Step 4: Create enhanced session metadata with recovery info
            createEnhancedSessionMetadata(sessionDir, id, recoveryResult)

            // Step 5: Create per-recorder subdirs with validation
            for (entry in recorders) {
                val sensorDir = File(sessionDir, entry.name)
                sensorDir.mkdirs()
                entry.state = RecorderState.STARTING
                entry.recoveryAttempts = 0 // Reset recovery attempts
            }
            updateSensorStates()

            // Step 6: Enhanced parallel sensor startup with recovery handling
            val successfulSensors = mutableListOf<String>()
            val failedSensors = mutableMapOf<String, String>()
            val recoveredSensors = mutableListOf<String>()

            for (entry in recorders) {
                val sub = File(sessionDir, entry.name)
                Log.d(TAG, "Starting ${entry.name} recorder with enhanced coordination")

                var startSuccess = false
                var attempts = 0
                val maxAttempts = if (entry.isRequired) MAX_RECOVERY_ATTEMPTS else 1

                while (!startSuccess && attempts < maxAttempts) {
                    attempts++
                    
                    try {
                        // Set recovery state if this is a retry
                        if (attempts > 1) {
                            entry.state = RecorderState.RECOVERING
                            updateSensorStates()
                            delay(RECOVERY_DELAY_MS)
                        }

                        entry.recorder.start(sub)
                        entry.state = RecorderState.RECORDING
                        entry.lastError = null
                        entry.recoveryAttempts = attempts - 1
                        
                        successfulSensors.add(entry.name)
                        if (attempts > 1) recoveredSensors.add(entry.name)
                        
                        startSuccess = true
                        Log.i(TAG, "Successfully started ${entry.name} recorder (attempt $attempts)")
                        
                    } catch (e: Exception) {
                        val errorMsg = "Failed to start ${entry.name} (attempt $attempts): ${e.message}"
                        Log.w(TAG, errorMsg, e)

                        entry.lastError = e.message
                        entry.recoveryAttempts = attempts

                        if (attempts >= maxAttempts) {
                            // All attempts failed
                            entry.state = RecorderState.ERROR
                            failedSensors[entry.name] = e.message ?: "Unknown error"

                            // Check if this is a required sensor
                            if (entry.isRequired) {
                                Log.e(TAG, "Required sensor ${entry.name} failed after $attempts attempts - aborting session")
                                safeStopAll()
                                updateSensorStates()
                                throw IllegalStateException("Required sensor ${entry.name} failed to start after $attempts attempts: ${e.message}")
                            }
                        }
                    }
                }
            }

            updateSensorStates()

            // Step 7: Check if we have at least one successful sensor
            if (successfulSensors.isEmpty()) {
                throw IllegalStateException("No sensors started successfully")
            }

            // Step 8: Session started successfully (potentially with partial failures)
            sessionRootDir = sessionDir
            _currentSessionId.value = id
            _state.value = SessionOrchestrator.State.RECORDING

            // Step 9: Start enhanced session health monitoring
            startSessionHealthMonitoring()

            val sessionResult = SessionStartResult(
                sessionId = id,
                successfulSensors = successfulSensors,
                failedSensors = failedSensors,
                isPartialSuccess = failedSensors.isNotEmpty(),
                hasRecoveredSensors = recoveredSensors.isNotEmpty(),
            )
            _lastSessionResult.value = sessionResult

            if (failedSensors.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Session $id started with partial failures. " +
                        "Successful: ${successfulSensors.joinToString()}, " +
                        "Failed: ${failedSensors.keys.joinToString()}, " +
                        "Recovered: ${recoveredSensors.joinToString()}",
                )
            } else {
                Log.i(TAG, "Session $id started successfully with all ${successfulSensors.size} recorders")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session: ${e.message}", e)
            _state.value = SessionOrchestrator.State.IDLE
            throw e
        }
    }

    /**
     * Perform crash recovery on session start
     */
    private suspend fun performCrashRecovery(): CrashRecoveryManager.RecoveryResult {
        return crashRecoveryManager?.performRecovery() ?: CrashRecoveryManager.RecoveryResult(
            totalSessionsScanned = 0,
            crashedSessionsFound = 0,
            crashedSessionIds = emptyList(),
            recoveryErrors = emptyList(),
        )
    }

    /**
     * Start session health monitoring with real-time error recovery
     */
    private fun startSessionHealthMonitoring() {
        healthMonitoringJob?.cancel()
        healthMonitoringJob = recoveryScope?.launch {
            while (isActive && _state.value == SessionOrchestrator.State.RECORDING) {
                try {
                    performHealthCheck()
                    delay(HEALTH_CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during health check: ${e.message}", e)
                    delay(HEALTH_CHECK_INTERVAL_MS * 2) // Back off on error
                }
            }
        }
    }

    /**
     * Perform health check on all active sensors
     */
    private suspend fun performHealthCheck() {
        val currentTime = System.currentTimeMillis()
        val sessionDuration = if (sessionStartTimestampMs > 0) currentTime - sessionStartTimestampMs else 0L
        
        val issues = mutableListOf<String>()
        var activeSensors = 0
        var erroredSensors = 0
        var recoveringSensors = 0

        for (entry in recorders) {
            entry.lastHealthCheck = currentTime
            
            when (entry.state) {
                RecorderState.RECORDING -> {
                    activeSensors++
                    // TODO: Could add sensor-specific health checks here
                }
                RecorderState.ERROR -> {
                    erroredSensors++
                    issues.add("${entry.name}: ${entry.lastError}")
                    
                    // Attempt recovery for non-required sensors
                    if (!entry.isRequired && entry.recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
                        attemptSensorRecovery(entry)
                    }
                }
                RecorderState.RECOVERING -> {
                    recoveringSensors++
                }
                else -> {
                    // Sensor in transitional state
                }
            }
        }

        val healthStatus = SessionHealthStatus(
            isHealthy = erroredSensors == 0 && activeSensors > 0,
            activeSensors = activeSensors,
            erroredSensors = erroredSensors,
            recoveringSensors = recoveringSensors,
            lastHealthCheck = currentTime,
            sessionDurationMs = sessionDuration,
            issues = issues,
        )

        _sessionHealth.value = healthStatus

        if (!healthStatus.isHealthy) {
            Log.w(TAG, "Session health issues detected: ${issues.joinToString(", ")}")
        }
    }

    /**
     * Attempt to recover a failed sensor
     */
    private suspend fun attemptSensorRecovery(entry: RecorderEntry) {
        if (entry.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(TAG, "Max recovery attempts reached for ${entry.name}")
            return
        }

        entry.state = RecorderState.RECOVERING
        entry.recoveryAttempts++
        updateSensorStates()

        Log.i(TAG, "Attempting recovery for ${entry.name} (attempt ${entry.recoveryAttempts})")

        try {
            delay(RECOVERY_DELAY_MS)

            // Try to stop and restart the sensor
            kotlin.runCatching { entry.recorder.stop() }
            delay(1000) // Brief pause between stop and start

            sessionRootDir?.let { sessionDir ->
                val sensorDir = File(sessionDir, entry.name)
                entry.recorder.start(sensorDir)
                
                entry.state = RecorderState.RECORDING
                entry.lastError = null
                
                Log.i(TAG, "Successfully recovered ${entry.name} sensor")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery attempt ${entry.recoveryAttempts} failed for ${entry.name}: ${e.message}", e)
            entry.state = RecorderState.ERROR
            entry.lastError = "Recovery failed: ${e.message}"
        }

        updateSensorStates()
    }

    /**
     * Enhanced prerequisite validation with crash recovery status
     */
    private suspend fun validateRecordingPrerequisites(recoveryResult: CrashRecoveryManager.RecoveryResult): SessionPrerequisites {
        val ctx = context
        val availableStorage = if (ctx != null) {
            val externalFilesDir = ctx.getExternalFilesDir(null)
            val root = externalFilesDir ?: ctx.filesDir
            root.freeSpace
        } else {
            Long.MAX_VALUE // Assume sufficient space if no context
        }

        return SessionPrerequisites(
            hasCameraPermission = checkCameraPermission(),
            hasStoragePermission = checkStoragePermission(),
            hasBluetoothPermission = checkBluetoothPermission(),
            availableStorageBytes = availableStorage,
            registeredSensors = recorders.map { it.name },
            crashRecoveryStatus = recoveryResult,
        )
    }

    /**
     * Enhanced stopSession with data validation and comprehensive cleanup
     * Stops the current session and all recorders, leaving files on disk.
     * Updates session metadata with completion information.
     * Handles partial failures gracefully - continues stopping other sensors even if some fail.
     * Performs session data validation and integrity checks.
     */
    override suspend fun stopSession() {
        if (_state.value != SessionOrchestrator.State.RECORDING) {
            Log.w(TAG, "stopSession called but not in RECORDING state: ${_state.value}")
            return
        }

        _state.value = SessionOrchestrator.State.STOPPING

        val sessionId = _currentSessionId.value
        val sessionDir = sessionRootDir
        val sessionEndTimestampNs = System.nanoTime()
        val sessionEndTimestampMs = System.currentTimeMillis()
        val sessionDurationMs = sessionEndTimestampMs - sessionStartTimestampMs

        Log.i(TAG, "Stopping enhanced session: $sessionId")
        Log.i(TAG, "Session duration: ${sessionDurationMs}ms")

        try {
            // Stop health monitoring first
            healthMonitoringJob?.cancel()
            healthMonitoringJob = null

            val stopResults = mutableMapOf<String, Boolean>()
            val stopErrors = mutableMapOf<String, String>()

            // Enhanced stop sequence with proper coordination
            for (entry in recorders) {
                if (entry.state == RecorderState.RECORDING || entry.state == RecorderState.RECOVERING) {
                    entry.state = RecorderState.STOPPING
                    updateSensorStates()

                    val result = runCatching {
                        // Give the recorder a moment to finish current operations
                        delay(100)
                        
                        entry.recorder.stop()
                        entry.state = RecorderState.STOPPED
                        entry.lastError = null
                        
                        Log.i(TAG, "Successfully stopped ${entry.name} recorder")
                        true
                        
                    }.getOrElse { e ->
                        Log.e(TAG, "Error stopping ${entry.name} recorder: ${e.message}", e)
                        entry.state = RecorderState.ERROR
                        entry.lastError = e.message
                        stopErrors[entry.name] = e.message ?: "Unknown error"
                        false
                    }
                    stopResults[entry.name] = result
                }
            }

            updateSensorStates()

            // Perform session data validation
            val validationResults = performSessionDataValidation(sessionDir)

            // Update enhanced session metadata with completion info and validation results
            if (sessionDir != null && sessionId != null) {
                updateEnhancedSessionMetadata(
                    sessionDir,
                    sessionId,
                    sessionEndTimestampMs,
                    sessionEndTimestampNs,
                    sessionDurationMs,
                    stopResults,
                    stopErrors,
                    validationResults,
                )
            }

            val successfulStops = stopResults.filterValues { it }.keys
            val failedStops = stopResults.filterValues { !it }.keys

            if (failedStops.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Session $sessionId stopped with some failures. " +
                        "Successful stops: ${successfulStops.joinToString()}, " +
                        "Failed stops: ${failedStops.joinToString()}",
                )
            } else {
                Log.i(TAG, "Session $sessionId stopped successfully. All sensors stopped cleanly.")
            }

            // Log validation results
            if (validationResults != null) {
                if (validationResults.isValid) {
                    Log.i(TAG, "Session data validation passed: ${validationResults.results.size} checks completed")
                } else {
                    Log.w(TAG, "Session data validation issues: ${validationResults.issues.joinToString(", ")}")
                }
            }

        } finally {
            // Always reset state regardless of stop success/failure
            _state.value = SessionOrchestrator.State.IDLE
            _currentSessionId.value = null
            sessionRootDir = null
            _sessionHealth.value = null

            // Reset all recorder states to IDLE
            for (entry in recorders) {
                if (entry.state != RecorderState.ERROR) {
                    entry.state = RecorderState.IDLE
                }
                entry.recoveryAttempts = 0 // Reset recovery attempts
            }
            updateSensorStates()
        }
    }

    /**
     * Perform session data validation after recording
     */
    private suspend fun performSessionDataValidation(sessionDir: File?): SessionDataValidator.ValidationResult? {
        return sessionDir?.let { dir ->
            try {
                Log.i(TAG, "Performing session data validation for: ${dir.name}")
                val result = SessionDataValidator.validateSessionData(dir)
                
                Log.i(TAG, "Validation completed: ${result.results.size} checks performed, " +
                           "${result.issues.size} issues found")
                
                if (result.issues.isNotEmpty()) {
                    Log.w(TAG, "Validation issues: ${result.issues.joinToString(", ")}")
                }
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error during session data validation: ${e.message}", e)
                null
            }
        }
    }



    private fun ensureSessionsRoot(): File {
        sessionsRootOverride?.let {
            if (!it.exists()) it.mkdirs()
            return it
        }
        val ctx = context
        val root: File =
            if (ctx != null) {
                val base: File? = ctx.getExternalFilesDir(null)
                File(base ?: ctx.filesDir, "sessions")
            } else {
                File(System.getProperty("java.io.tmpdir"), "sessions")
            }
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun generateSessionId(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val ts = sdf.format(Date())
        val shortUuid = UUID.randomUUID().toString().substring(0, 8)
        val model = Build.MODEL?.replace(" ", "_") ?: "device"
        return "${ts}_${model}_$shortUuid"
    }

    private suspend fun safeStopAll() {
        for (entry in recorders) {
            if (entry.state == RecorderState.RECORDING || entry.state == RecorderState.STARTING) {
                entry.state = RecorderState.STOPPING
                runCatching {
                    entry.recorder.stop()
                    entry.state = RecorderState.STOPPED
                }.getOrElse {
                    entry.state = RecorderState.ERROR
                    entry.lastError = it.message
                }
            }
        }
        updateSensorStates()
    }

    /**
     * Validate all prerequisites for starting a recording session
     */
    private fun validateRecordingPrerequisites(): SessionPrerequisites {
        val ctx = context
        val availableStorage = if (ctx != null) {
            val externalFilesDir = ctx.getExternalFilesDir(null)
            val root = externalFilesDir ?: ctx.filesDir
            root.freeSpace
        } else {
            Long.MAX_VALUE // Assume sufficient space if no context
        }

        return SessionPrerequisites(
            hasCameraPermission = checkCameraPermission(),
            hasStoragePermission = checkStoragePermission(),
            hasBluetoothPermission = checkBluetoothPermission(),
            availableStorageBytes = availableStorage,
            registeredSensors = recorders.map { it.name },
        )
    }

    /**
     * Check if minimum requirements are met for recording
     */
    private fun meetsMinimumRequirements(prerequisites: SessionPrerequisites): Boolean {
        return prerequisites.availableStorageBytes >= prerequisites.minimumRequiredBytes &&
            prerequisites.registeredSensors.isNotEmpty()
    }

    /**
     * Get human-readable errors for prerequisite failures
     */
    private fun getPrerequisiteErrors(prerequisites: SessionPrerequisites): String {
        val errors = mutableListOf<String>()

        if (!prerequisites.hasCameraPermission) {
            errors.add("Camera permission not granted")
        }
        if (!prerequisites.hasStoragePermission) {
            errors.add("Storage permission not granted")
        }
        if (!prerequisites.hasBluetoothPermission) {
            errors.add("Bluetooth permission not granted")
        }
        if (prerequisites.availableStorageBytes < prerequisites.minimumRequiredBytes) {
            val availableMB = prerequisites.availableStorageBytes / (1024 * 1024)
            val requiredMB = prerequisites.minimumRequiredBytes / (1024 * 1024)
            errors.add("Insufficient storage: ${availableMB}MB available, ${requiredMB}MB required")
        }
        if (prerequisites.registeredSensors.isEmpty()) {
            errors.add("No sensors registered")
        }

        return errors.joinToString(", ")
    }

    /**
     * Check camera permission
     */
    private fun checkCameraPermission(): Boolean {
        val ctx = context ?: return true // Assume granted if no context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Check storage permission
     */
    private fun checkStoragePermission(): Boolean {
        val ctx = context ?: return true // Assume granted if no context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // Scoped storage on Q+
        } else {
            true
        }
    }

    /**
     * Check Bluetooth permission
     */
    private fun checkBluetoothPermission(): Boolean {
        val ctx = context ?: return true // Assume granted if no context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasBluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val hasBluetoothAdmin = ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            hasBluetoothConnect && hasBluetoothAdmin
        } else {
            true
        }
    }

    /**
     * Create enhanced session metadata file with crash recovery information
     */
    private fun createEnhancedSessionMetadata(
        sessionDir: File,
        sessionId: String,
        recoveryResult: CrashRecoveryManager.RecoveryResult,
    ) {
        try {
            val metadataFile = File(sessionDir, "session_metadata.json")
            
            // Create enhanced metadata using safe JSON construction
            val metadata = try {
                val recordersArray = JSONArray()
                val expectedFilesArray = JSONArray()
                val storageInfoObject = JSONObject()
                val recoveryInfoObject = JSONObject().apply {
                    put("crashed_sessions_found", recoveryResult.crashedSessionsFound)
                    put("total_sessions_scanned", recoveryResult.totalSessionsScanned)
                    put("crashed_session_ids", JSONArray(recoveryResult.crashedSessionIds))
                    put("recovery_errors", JSONArray(recoveryResult.recoveryErrors))
                }

                // Add detailed recorder info and expected file structure
                recorders.forEach { entry ->
                    recordersArray.put(entry.name)

                    // Document expected file structure for each recorder
                    when (entry.name.lowercase()) {
                        "rgb" -> {
                            expectedFilesArray.put("${entry.name}/video.mp4")
                            expectedFilesArray.put("${entry.name}/frames/frame_*.jpg")
                            expectedFilesArray.put("${entry.name}/rgb_frames.csv")
                        }
                        "thermal" -> {
                            expectedFilesArray.put("${entry.name}/thermal_data.csv")
                            expectedFilesArray.put("${entry.name}/thermal_images/")
                        }
                        "gsr" -> {
                            expectedFilesArray.put("${entry.name}/gsr.csv")
                        }
                        "audio" -> {
                            expectedFilesArray.put("${entry.name}/audio.aac")
                            expectedFilesArray.put("${entry.name}/audio_events.csv")
                        }
                        else -> {
                            expectedFilesArray.put("${entry.name}/data.csv")
                        }
                    }
                }

                // Add storage information for monitoring
                val sessionsRoot = ensureSessionsRoot()
                val statsFs = android.os.StatFs(sessionsRoot.path)
                val availableBytes = statsFs.availableBytes
                val totalBytes = statsFs.totalBytes

                storageInfoObject.apply {
                    put("available_bytes", availableBytes)
                    put("total_bytes", totalBytes)
                    put("available_mb", availableBytes / (1024 * 1024))
                    put("total_mb", totalBytes / (1024 * 1024))
                    put("usage_percent", ((totalBytes - availableBytes).toFloat() / totalBytes * 100f).toInt())
                }

                val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

                JSONObject()
                    .put("session_id", sessionId)
                    .put("start_timestamp_ms", sessionStartTimestampMs)
                    .put("start_timestamp_ns", sessionStartTimestampNs)
                    .put("device_model", Build.MODEL ?: "unknown")
                    .put("device_manufacturer", Build.MANUFACTURER ?: "unknown")
                    .put("android_version", Build.VERSION.RELEASE ?: "unknown")
                    .put("app_version", "1.0.0")
                    .put("recorders", recordersArray)
                    .put("expected_files", expectedFilesArray)
                    .put("storage_info", storageInfoObject)
                    .put("recovery_info", recoveryInfoObject)
                    .put("estimated_session_size_mb", 1000) // Estimate 1GB per session
                    .put("session_status", "STARTED")
                    .put("synchronization_reference", "System time: System.currentTimeMillis() + System.nanoTime()")
                    .put("timezone", java.util.TimeZone.getDefault().id)
                    .put("created_at", dateFormatter.format(Date(sessionStartTimestampMs)))
                    .put(
                        "file_structure_info",
                        JSONObject().apply {
                            put("description", "Session data organized by sensor modality")
                            put("timestamp_format", "nanosecond precision for synchronization")
                            put("csv_format", "All CSV files include timestamp_ns and timestamp_ms columns")
                            put("sync_notes", "All timestamps use common system reference for cross-sensor alignment")
                        },
                    )
                    .toString(2) // Pretty print with 2-space indent
            } catch (jsonError: Exception) {
                Log.e(TAG, "Failed to create enhanced session metadata JSON", jsonError)
                // Fallback to basic metadata
                """
                {
                    "session_id": "$sessionId",
                    "start_timestamp_ms": $sessionStartTimestampMs,
                    "session_status": "STARTED",
                    "recovery_info": {
                        "crashed_sessions_found": ${recoveryResult.crashedSessionsFound},
                        "recovery_errors": ${recoveryResult.recoveryErrors.size}
                    },
                    "error": "JSON creation failed: ${jsonError.message}"
                }
                """.trimIndent()
            }

            metadataFile.writeText(metadata, Charsets.UTF_8)
            Log.d(TAG, "Enhanced session metadata created: ${metadataFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create enhanced session metadata: ${e.message}", e)
        }
    }

    /**
     * Update enhanced session metadata file with completion information and validation results
     */
    private fun updateEnhancedSessionMetadata(
        sessionDir: File,
        sessionId: String,
        endTimestampMs: Long,
        endTimestampNs: Long,
        durationMs: Long,
        stopResults: Map<String, Boolean>,
        stopErrors: Map<String, String> = emptyMap(),
        validationResults: SessionDataValidator.ValidationResult? = null,
    ) {
        try {
            val metadataFile = File(sessionDir, "session_metadata.json")
            val allRecordersSuccess = stopResults.values.all { it }
            val failedRecorders = stopResults.filterNot { it.value }.keys.toList()

            // Create enhanced completion metadata using safe JSON construction
            val metadata = try {
                val recordersArray = JSONArray()
                recorders.forEach { recordersArray.put(it.name) }

                val failedRecordersArray = JSONArray()
                failedRecorders.forEach { failedRecordersArray.put(it) }

                val recorderResultsObject = JSONObject()
                stopResults.forEach { (key, value) -> recorderResultsObject.put(key, value) }

                val stopErrorsObject = JSONObject()
                stopErrors.forEach { (key, value) -> stopErrorsObject.put(key, value) }

                // Add validation results if available
                val validationObject = JSONObject().apply {
                    if (validationResults != null) {
                        put("is_valid", validationResults.isValid)
                        put("validated_checks", validationResults.results.size)
                        put("issues", JSONArray(validationResults.issues))
                        put("validation_timestamp", System.currentTimeMillis())
                    } else {
                        put("validation_performed", false)
                        put("validation_error", "Validation could not be performed")
                    }
                }

                val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

                JSONObject()
                    .put("session_id", sessionId)
                    .put("start_timestamp_ms", sessionStartTimestampMs)
                    .put("start_timestamp_ns", sessionStartTimestampNs)
                    .put("end_timestamp_ms", endTimestampMs)
                    .put("end_timestamp_ns", endTimestampNs)
                    .put("duration_ms", durationMs)
                    .put("duration_formatted", formatDuration(durationMs))
                    .put("device_model", Build.MODEL ?: "unknown")
                    .put("device_manufacturer", Build.MANUFACTURER ?: "unknown")
                    .put("android_version", Build.VERSION.RELEASE ?: "unknown")
                    .put("app_version", "1.0.0")
                    .put("recorders", recordersArray)
                    .put("session_status", if (allRecordersSuccess) "COMPLETED" else "COMPLETED_WITH_ERRORS")
                    .put("failed_recorders", failedRecordersArray)
                    .put("recorder_results", recorderResultsObject)
                    .put("stop_errors", stopErrorsObject)
                    .put("validation_results", validationObject)
                    .put("created_at", dateFormatter.format(Date(sessionStartTimestampMs)))
                    .put("completed_at", dateFormatter.format(Date(endTimestampMs)))
                    .toString(2) // Pretty print with 2-space indent
            } catch (jsonError: Exception) {
                Log.e(TAG, "Failed to create enhanced completion metadata JSON", jsonError)
                // Fallback to basic metadata
                """
                {
                    "session_id": "$sessionId",
                    "end_timestamp_ms": $endTimestampMs,
                    "duration_ms": $durationMs,
                    "session_status": "${if (allRecordersSuccess) "COMPLETED" else "COMPLETED_WITH_ERRORS"}",
                    "validation_performed": ${validationResults != null},
                    "error": "JSON creation failed: ${jsonError.message}"
                }
                """.trimIndent()
            }

            metadataFile.writeText(metadata, Charsets.UTF_8)
            Log.d(TAG, "Enhanced session metadata updated: ${metadataFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update enhanced session metadata: ${e.message}", e)
        }
    }

    /**
     * Format duration in human-readable format
     */
    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60))
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Enhanced cleanup method with resource management
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up enhanced RecordingController")
        
        try {
            // Cancel health monitoring
            healthMonitoringJob?.cancel()
            healthMonitoringJob = null
            
            // Cancel recovery scope
            recoveryScope?.cancel()
            recoveryScope = null
            
            // Reset all state
            _state.value = SessionOrchestrator.State.IDLE
            _currentSessionId.value = null
            _sessionHealth.value = null
            sessionRootDir = null
            
            // Reset recorder states
            for (entry in recorders) {
                entry.state = RecorderState.IDLE
                entry.lastError = null
                entry.recoveryAttempts = 0
            }
            updateSensorStates()
            
            Log.i(TAG, "Enhanced RecordingController cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during enhanced RecordingController cleanup: ${e.message}", e)
        }
    }

    /**
     * Update session metadata file with completion information
     */
    private fun updateSessionMetadata(
        sessionDir: File,
        sessionId: String,
        endTimestampMs: Long,
        endTimestampNs: Long,
        durationMs: Long,
        stopResults: Map<String, Boolean>,
        stopErrors: Map<String, String> = emptyMap(),
    ) {
        try {
            val metadataFile = File(sessionDir, "session_metadata.json")
            val allRecordersSuccess = stopResults.values.all { it }
            val failedRecorders = stopResults.filterNot { it.value }.keys.toList()

            // Create completion metadata using safe JSON construction
            val metadata = try {
                val recordersArray = JSONArray()
                recorders.forEach { recordersArray.put(it.name) }

                val failedRecordersArray = JSONArray()
                failedRecorders.forEach { failedRecordersArray.put(it) }

                val recorderResultsObject = JSONObject()
                stopResults.forEach { (key, value) -> recorderResultsObject.put(key, value) }

                val stopErrorsObject = JSONObject()
                stopErrors.forEach { (key, value) -> stopErrorsObject.put(key, value) }

                val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)

                JSONObject()
                    .put("session_id", sessionId)
                    .put("start_timestamp_ms", sessionStartTimestampMs)
                    .put("start_timestamp_ns", sessionStartTimestampNs)
                    .put("end_timestamp_ms", endTimestampMs)
                    .put("end_timestamp_ns", endTimestampNs)
                    .put("duration_ms", durationMs)
                    .put("device_model", Build.MODEL ?: "unknown")
                    .put("device_manufacturer", Build.MANUFACTURER ?: "unknown")
                    .put("android_version", Build.VERSION.RELEASE ?: "unknown")
                    .put("app_version", "1.0.0")
                    .put("recorders", recordersArray)
                    .put("session_status", if (allRecordersSuccess) "COMPLETED" else "COMPLETED_WITH_ERRORS")
                    .put("failed_recorders", failedRecordersArray)
                    .put("recorder_results", recorderResultsObject)
                    .put("stop_errors", stopErrorsObject)
                    .put("created_at", dateFormatter.format(java.util.Date(sessionStartTimestampMs)))
                    .put("completed_at", dateFormatter.format(java.util.Date(endTimestampMs)))
                    .toString(2) // Pretty print with 2-space indent
            } catch (jsonError: Exception) {
                Log.e("RecordingController", "Failed to create completion metadata JSON", jsonError)
                // Fallback to basic metadata
                """
                {
                    "session_id": "$sessionId",
                    "end_timestamp_ms": $endTimestampMs,
                    "duration_ms": $durationMs,
                    "session_status": "${if (allRecordersSuccess) "COMPLETED" else "COMPLETED_WITH_ERRORS"}",
                    "error": "JSON creation failed: ${jsonError.message}"
                }
                """.trimIndent()
            }

            metadataFile.writeText(metadata, Charsets.UTF_8)
            Log.d("RecordingController", "Session metadata updated: ${metadataFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("RecordingController", "Failed to update session metadata: ${e.message}", e)
        }
    }

    /**
     * Get session timing information for external use
     */
    fun getSessionTiming(): SessionTiming? {
        return if (sessionStartTimestampNs > 0L) {
            SessionTiming(
                startTimestampNs = sessionStartTimestampNs,
                startTimestampMs = sessionStartTimestampMs,
                sessionId = _currentSessionId.value,
            )
        } else {
            null
        }
    }

    // SessionOrchestrator interface implementations

    override fun getCurrentSessionDirectory(): File? {
        return sessionRootDir
    }

    override fun getSessionsRootDirectory(): File {
        return ensureSessionsRoot()
    }

    override fun getRegisteredSensors(): List<String> {
        return recorders.map { it.name }
    }

    /**
     * Session timing information for multi-modal data alignment
     */
    data class SessionTiming(
        val startTimestampNs: Long,
        val startTimestampMs: Long,
        val sessionId: String?,
    )



    /**
     * Mark a session as crashed in its metadata
     */
    private fun markSessionAsCrashed(sessionDir: File, originalMetadata: JSONObject) {
        try {
            val crashTimestamp = System.currentTimeMillis()
            val updatedMetadata = originalMetadata
                .put("session_status", "CRASHED")
                .put("crash_detected_at", crashTimestamp)
                .put("recovery_note", "Session was interrupted and recovered on app restart")

            val metadataFile = File(sessionDir, "session_metadata.json")
            metadataFile.writeText(updatedMetadata.toString(2), Charsets.UTF_8)

            Log.i("RecordingController", "Marked session ${sessionDir.name} as crashed")
        } catch (e: Exception) {
            Log.e("RecordingController", "Failed to mark session as crashed: ${e.message}", e)
        }
    }

    /**
     * Handle sensor disconnection during recording (for external monitoring)
     */
    suspend fun handleSensorDisconnection(sensorName: String, error: String) {
        val entry = recorders.find { it.name == sensorName }
        if (entry != null && entry.state == RecorderState.RECORDING) {
            Log.w("RecordingController", "Sensor $sensorName disconnected during recording: $error")

            entry.state = RecorderState.ERROR
            entry.lastError = error
            updateSensorStates()

            // Attempt to stop the failed sensor gracefully
            try {
                entry.recorder.stop()
                entry.state = RecorderState.STOPPED
            } catch (e: Exception) {
                Log.e("RecordingController", "Failed to stop disconnected sensor $sensorName: ${e.message}", e)
            }

            // Check if this was a required sensor
            if (entry.isRequired) {
                Log.e("RecordingController", "Required sensor $sensorName disconnected - stopping entire session")
                stopSession()
            } else {
                Log.i("RecordingController", "Optional sensor $sensorName disconnected - continuing with other sensors")
                updateSensorStates()
            }
        }
    }

    /**
     * Attempt to reconnect a disconnected sensor
     */
    suspend fun attemptSensorReconnection(sensorName: String): Boolean {
        val entry = recorders.find { it.name == sensorName }
        if (entry != null && entry.state == RecorderState.ERROR) {
            Log.i("RecordingController", "Attempting to reconnect sensor: $sensorName")

            try {
                // If we're in a recording session, try to restart the sensor
                if (_state.value == SessionOrchestrator.State.RECORDING && sessionRootDir != null) {
                    val sensorDir = File(sessionRootDir!!, sensorName)
                    entry.recorder.start(sensorDir)
                    entry.state = RecorderState.RECORDING
                    entry.lastError = null
                    updateSensorStates()

                    Log.i("RecordingController", "Successfully reconnected sensor: $sensorName")
                    return true
                }
            } catch (e: Exception) {
                Log.e("RecordingController", "Failed to reconnect sensor $sensorName: ${e.message}", e)
                entry.lastError = e.message
                updateSensorStates()
            }
        }
        return false
    }

    /**
     * Get current status of all sensors for UI display
     */
    fun getSensorStatusReport(): Map<String, String> {
        return recorders.associate { entry ->
            val status = when (entry.state) {
                RecorderState.IDLE -> "Ready"
                RecorderState.STARTING -> "Starting..."
                RecorderState.RECORDING -> "Recording"
                RecorderState.STOPPING -> "Stopping..."
                RecorderState.STOPPED -> "Stopped"
                RecorderState.ERROR -> "Error: ${entry.lastError ?: "Unknown"}"
                RecorderState.RECOVERING -> "Recovering..."
            }
            entry.name to status
        }
    }
}
