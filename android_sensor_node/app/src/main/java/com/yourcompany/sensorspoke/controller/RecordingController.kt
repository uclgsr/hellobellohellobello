package com.yourcompany.sensorspoke.controller

import android.content.Context
import android.os.Build
import android.util.Log
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Enhanced RecordingController implements SessionOrchestrator to coordinate start/stop across sensor recorders
 * and manages the lifecycle of a recording session, including session directory creation.
 *
 * This is the central session orchestrator mentioned in the MVP architecture that:
 * - Holds each sensor's SensorRecorder implementation
 * - Starts and stops them together with synchronized timing
 * - Creates session directory and sub-folders per sensor
 * - Provides synchronized session management with common timestamp reference for multi-modal data alignment
 * - Handles partial failures gracefully (continues recording with available sensors)
 * - Performs prerequisite validation before starting sessions
 * - Provides recovery mechanisms for crashed or interrupted sessions
 */
class RecordingController(
    private val context: Context?,
    private val sessionsRootOverride: File? = null,
) : SessionOrchestrator {
    data class RecorderEntry(
        val name: String,
        val recorder: SensorRecorder,
        var state: RecorderState = RecorderState.IDLE,
        var isRequired: Boolean = false, // Whether this recorder is required for session to proceed
        var lastError: String? = null,
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
    }

    /**
     * Prerequisites that must be validated before starting a session
     */
    data class SessionPrerequisites(
        val hasCameraPermission: Boolean,
        val hasStoragePermission: Boolean,
        val hasBluetoothPermission: Boolean,
        val availableStorageBytes: Long,
        val minimumRequiredBytes: Long = 100 * 1024 * 1024, // 100MB minimum
        val registeredSensors: List<String>,
    )

    /**
     * Result of a session start attempt, including partial failures
     */
    data class SessionStartResult(
        val sessionId: String,
        val successfulSensors: List<String>,
        val failedSensors: Map<String, String>, // sensor name -> error message
        val isPartialSuccess: Boolean,
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

    private val recorders = mutableListOf<RecorderEntry>()
    private var sessionRootDir: File? = null

    // Synchronized session timing for multi-modal alignment
    private var sessionStartTimestampNs: Long = 0L
    private var sessionStartTimestampMs: Long = 0L

    // Session recovery state
    private var isRecoveryMode = false

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

            val prerequisites = validateRecordingPrerequisites()
            if (!meetsMinimumRequirements(prerequisites)) {
                throw IllegalStateException("Prerequisites not met: ${getPrerequisiteErrors(prerequisites)}")
            }

            val id = sessionId ?: generateSessionId()
            val root = ensureSessionsRoot()
            val sessionDir = File(root, id)
            if (!sessionDir.exists()) sessionDir.mkdirs()

            // Step 3: Capture synchronized session start timestamps
            sessionStartTimestampNs = System.nanoTime()
            sessionStartTimestampMs = System.currentTimeMillis()

            Log.i("RecordingController", "Starting session: $id with ${recorders.size} recorders")
            Log.i("RecordingController", "Session start timestamp: ${sessionStartTimestampMs}ms / ${sessionStartTimestampNs}ns")

            // Step 4: Create session metadata file
            createSessionMetadata(sessionDir, id)

            // Step 5: Create per-recorder subdirs first
            for (entry in recorders) {
                File(sessionDir, entry.name).mkdirs()
                entry.state = RecorderState.STARTING
            }
            updateSensorStates()

            // Step 6: Start all recorders with partial failure handling
            val successfulSensors = mutableListOf<String>()
            val failedSensors = mutableMapOf<String, String>()

            for (entry in recorders) {
                val sub = File(sessionDir, entry.name)
                Log.d("RecordingController", "Starting ${entry.name} recorder")
                
                try {
                    entry.recorder.start(sub)
                    entry.state = RecorderState.RECORDING
                    entry.lastError = null
                    successfulSensors.add(entry.name)
                    Log.i("RecordingController", "Successfully started ${entry.name} recorder")
                } catch (e: Exception) {
                    val errorMsg = "Failed to start ${entry.name}: ${e.message}"
                    Log.e("RecordingController", errorMsg, e)
                    
                    entry.state = RecorderState.ERROR
                    entry.lastError = e.message
                    failedSensors[entry.name] = e.message ?: "Unknown error"
                    
                    // Check if this is a required sensor
                    if (entry.isRequired) {
                        // Required sensor failed - abort entire session
                        Log.e("RecordingController", "Required sensor ${entry.name} failed - aborting session")
                        safeStopAll()
                        updateSensorStates()
                        throw IllegalStateException("Required sensor ${entry.name} failed to start: ${e.message}")
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

            val sessionResult = SessionStartResult(
                sessionId = id,
                successfulSensors = successfulSensors,
                failedSensors = failedSensors,
                isPartialSuccess = failedSensors.isNotEmpty()
            )
            _lastSessionResult.value = sessionResult

            if (failedSensors.isNotEmpty()) {
                Log.w("RecordingController", "Session $id started with partial failures. " +
                        "Successful: ${successfulSensors.joinToString()}, " +
                        "Failed: ${failedSensors.keys.joinToString()}")
            } else {
                Log.i("RecordingController", "Session $id started successfully with all ${successfulSensors.size} recorders")
            }

        } catch (t: Throwable) {
            Log.e("RecordingController", "Failed to start session: ${t.message}", t)
            // Best-effort cleanup: stop any started recorders
            safeStopAll()
            _currentSessionId.value = null
            sessionRootDir = null
            _state.value = SessionOrchestrator.State.IDLE
            _lastSessionResult.value = null
            updateSensorStates()
            throw t
        }
    }

    /**
     * Stops the current session and all recorders, leaving files on disk.
     * Updates session metadata with completion information.
     * Handles partial failures gracefully - continues stopping other sensors even if some fail.
     */
    override suspend fun stopSession() {
        if (_state.value != SessionOrchestrator.State.RECORDING) {
            Log.w("RecordingController", "stopSession called but not in RECORDING state: ${_state.value}")
            return
        }
        
        _state.value = SessionOrchestrator.State.STOPPING

        val sessionId = _currentSessionId.value
        val sessionDir = sessionRootDir
        val sessionEndTimestampNs = System.nanoTime()
        val sessionEndTimestampMs = System.currentTimeMillis()
        val sessionDurationMs = sessionEndTimestampMs - sessionStartTimestampMs

        Log.i("RecordingController", "Stopping session: $sessionId")
        Log.i("RecordingController", "Session duration: ${sessionDurationMs}ms")

        try {
            val stopResults = mutableMapOf<String, Boolean>()
            val stopErrors = mutableMapOf<String, String>()

            // Stop all recorders, continuing even if some fail
            for (entry in recorders) {
                if (entry.state == RecorderState.RECORDING) {
                    entry.state = RecorderState.STOPPING
                    updateSensorStates()
                    
                    val result = runCatching {
                        entry.recorder.stop()
                        entry.state = RecorderState.STOPPED
                        entry.lastError = null
                        true
                    }.getOrElse { e ->
                        Log.e("RecordingController", "Error stopping ${entry.name} recorder: ${e.message}", e)
                        entry.state = RecorderState.ERROR
                        entry.lastError = e.message
                        stopErrors[entry.name] = e.message ?: "Unknown error"
                        false
                    }
                    stopResults[entry.name] = result
                }
            }

            updateSensorStates()

            // Update session metadata with completion info
            if (sessionDir != null && sessionId != null) {
                updateSessionMetadata(
                    sessionDir, 
                    sessionId, 
                    sessionEndTimestampMs, 
                    sessionEndTimestampNs, 
                    sessionDurationMs, 
                    stopResults,
                    stopErrors
                )
            }

            val successfulStops = stopResults.filterValues { it }.keys
            val failedStops = stopResults.filterValues { !it }.keys

            if (failedStops.isNotEmpty()) {
                Log.w("RecordingController", "Session $sessionId stopped with some failures. " +
                        "Successful stops: ${successfulStops.joinToString()}, " +
                        "Failed stops: ${failedStops.joinToString()}")
            } else {
                Log.i("RecordingController", "Session $sessionId stopped successfully. Results: $stopResults")
            }

        } finally {
            // Always reset state regardless of stop success/failure
            _state.value = SessionOrchestrator.State.IDLE
            _currentSessionId.value = null
            sessionRootDir = null
            
            // Reset all recorder states to IDLE
            for (entry in recorders) {
                if (entry.state != RecorderState.ERROR) {
                    entry.state = RecorderState.IDLE
                }
            }
            updateSensorStates()
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
            registeredSensors = recorders.map { it.name }
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
     * Validate that sufficient storage space is available for recording
     */
    private fun validateStorageSpace() {
        try {
            val sessionsRoot = ensureSessionsRoot()
            val statsFs = android.os.StatFs(sessionsRoot.path)
            val availableBytes = statsFs.availableBytes
            val availableMB = availableBytes / (1024 * 1024)
            
            // Require at least 100MB free space for recording
            // This accounts for video files, images, and CSV data
            val requiredMB = 100L
            
            Log.i("RecordingController", "Available storage: ${availableMB}MB, required: ${requiredMB}MB")
            
            if (availableMB < requiredMB) {
                throw IllegalStateException(
                    "Insufficient storage space for recording. Available: ${availableMB}MB, Required: ${requiredMB}MB"
                )
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                throw e
            }
            Log.w("RecordingController", "Failed to check storage space: ${e.message}", e)
            // Don't block recording if we can't check storage space
        }
    }

    /**
     * Create session metadata file with synchronized timing information
     * Enhanced with comprehensive session information and file structure documentation
     */
    private fun createSessionMetadata(sessionDir: File, sessionId: String) {
        try {
            val metadataFile = File(sessionDir, "session_metadata.json")

            // Create metadata using safe JSON construction
            val metadata = try {
                val recordersArray = JSONArray()
                val expectedFilesArray = JSONArray()
                val storageInfoObject = JSONObject()
                
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

                val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)

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
                    .put("estimated_session_size_mb", calculateEstimatedSessionSize())
                    .put("session_status", "STARTED")
                    .put("synchronization_reference", "System time: System.currentTimeMillis() + System.nanoTime()")
                    .put("timezone", java.util.TimeZone.getDefault().id)
                    .put("created_at", dateFormatter.format(java.util.Date(sessionStartTimestampMs)))
                    .put("file_structure_info", JSONObject().apply {
                        put("description", "Session data organized by sensor modality")
                        put("timestamp_format", "nanosecond precision for synchronization")
                        put("csv_format", "All CSV files include timestamp_ns and timestamp_ms columns")
                        put("sync_notes", "All timestamps use common system reference for cross-sensor alignment")
                    })
                    .toString(2) // Pretty print with 2-space indent
            } catch (jsonError: Exception) {
                Log.e("RecordingController", "Failed to create session metadata JSON", jsonError)
                // Fallback to basic metadata
                """
                {
                    "session_id": "$sessionId",
                    "start_timestamp_ms": $sessionStartTimestampMs,
                    "session_status": "STARTED",
                    "error": "JSON creation failed: ${jsonError.message}"
                }
                """.trimIndent()
            }

            metadataFile.writeText(metadata, Charsets.UTF_8)
            Log.d("RecordingController", "Enhanced session metadata created: ${metadataFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("RecordingController", "Failed to create session metadata: ${e.message}", e)
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
     * Detect and handle crashed/interrupted sessions on startup
     */
    suspend fun performCrashRecovery() {
        Log.i("RecordingController", "Performing crash recovery check")
        
        try {
            val sessionsRoot = ensureSessionsRoot()
            val sessionDirs = sessionsRoot.listFiles { file -> file.isDirectory } ?: return
            
            for (sessionDir in sessionDirs) {
                val metadataFile = File(sessionDir, "session_metadata.json")
                if (metadataFile.exists()) {
                    val metadata = metadataFile.readText(Charsets.UTF_8)
                    val json = JSONObject(metadata)
                    val status = json.optString("session_status", "")
                    
                    if (status == "STARTED") {
                        // Found an unfinished session - mark it as crashed
                        Log.w("RecordingController", "Found crashed session: ${sessionDir.name}")
                        markSessionAsCrashed(sessionDir, json)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RecordingController", "Error during crash recovery: ${e.message}", e)
        }
    }

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
            }
            entry.name to status
        }
    }
}
