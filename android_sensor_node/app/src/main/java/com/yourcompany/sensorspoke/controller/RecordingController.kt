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
 * RecordingController implements SessionOrchestrator to coordinate start/stop across sensor recorders
 * and manages the lifecycle of a recording session, including session directory creation.
 *
 * This is the central session orchestrator mentioned in the MVP architecture that:
 * - Holds each sensor's SensorRecorder implementation
 * - Starts and stops them together with synchronized timing
 * - Creates session directory and sub-folders per sensor
 * - Provides synchronized session management with common timestamp reference for multi-modal data alignment
 */
class RecordingController(
    private val context: Context?,
    private val sessionsRootOverride: File? = null,
) : SessionOrchestrator {
    data class RecorderEntry(
        val name: String,
        val recorder: SensorRecorder,
    )

    // Use SessionOrchestrator.State for consistent interface
    private val _state = MutableStateFlow(SessionOrchestrator.State.IDLE)
    override val state: StateFlow<SessionOrchestrator.State> = _state

    private val _currentSessionId = MutableStateFlow<String?>(null)
    override val currentSessionId: StateFlow<String?> = _currentSessionId

    private val recorders = mutableListOf<RecorderEntry>()
    private var sessionRootDir: File? = null

    // Synchronized session timing for multi-modal alignment
    private var sessionStartTimestampNs: Long = 0L
    private var sessionStartTimestampMs: Long = 0L

    /**
     * Register a recorder under a unique name; its data will be stored under sessionDir/<name>.
     */
    override fun register(
        name: String,
        recorder: SensorRecorder,
    ) {
        require(recorders.none { it.name == name }) { "Recorder name '$name' already registered" }
        recorders.add(RecorderEntry(name, recorder))
    }

    /**
     * Unregister a recorder by name.
     */
    override fun unregister(name: String) {
        recorders.removeAll { it.name == name }
    }

    /**
     * Add a recorder (alias for register for backward compatibility).
     */
    fun addRecorder(name: String, recorder: SensorRecorder) {
        register(name, recorder)
    }

    /**
     * Starts a new session with an optional provided sessionId. When null, a new id is generated.
     * Creates the session directory tree and starts all registered recorders with synchronized timing.
     */
    override suspend fun startSession(sessionId: String?) {
        if (_state.value != SessionOrchestrator.State.IDLE) return
        _state.value = SessionOrchestrator.State.PREPARING
        try {
            // Validate storage space before starting recording
            validateStorageSpace()
            
            val id = sessionId ?: generateSessionId()
            val root = ensureSessionsRoot()
            val sessionDir = File(root, id)
            if (!sessionDir.exists()) sessionDir.mkdirs()

            // Capture synchronized session start timestamps
            sessionStartTimestampNs = System.nanoTime()
            sessionStartTimestampMs = System.currentTimeMillis()

            Log.i("RecordingController", "Starting session: $id with ${recorders.size} recorders")
            Log.i("RecordingController", "Session start timestamp: ${sessionStartTimestampMs}ms / ${sessionStartTimestampNs}ns")

            // Create session metadata file
            createSessionMetadata(sessionDir, id)

            // Create per-recorder subdirs first
            for (entry in recorders) {
                File(sessionDir, entry.name).mkdirs()
            }
            // Start all recorders with synchronized timing
            for (entry in recorders) {
                val sub = File(sessionDir, entry.name)
                Log.d("RecordingController", "Starting ${entry.name} recorder")
                entry.recorder.start(sub)
            }
            sessionRootDir = sessionDir
            _currentSessionId.value = id
            _state.value = SessionOrchestrator.State.RECORDING

            Log.i("RecordingController", "Session $id started successfully with all recorders")
        } catch (t: Throwable) {
            Log.e("RecordingController", "Failed to start session: ${t.message}", t)
            // Best-effort cleanup: stop any started recorders
            safeStopAll()
            _currentSessionId.value = null
            sessionRootDir = null
            _state.value = SessionOrchestrator.State.IDLE
            throw t
        }
    }

    /**
     * Stops the current session and all recorders, leaving files on disk.
     * Updates session metadata with completion information.
     */
    override suspend fun stopSession() {
        if (_state.value != SessionOrchestrator.State.RECORDING) return
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

            for (entry in recorders) {
                val result = runCatching {
                    entry.recorder.stop()
                    true
                }.getOrElse { e ->
                    Log.e("RecordingController", "Error stopping ${entry.name} recorder: ${e.message}", e)
                    false
                }
                stopResults[entry.name] = result
            }

            // Update session metadata with completion info
            if (sessionDir != null && sessionId != null) {
                updateSessionMetadata(sessionDir, sessionId, sessionEndTimestampMs, sessionEndTimestampNs, sessionDurationMs, stopResults)
            }

            Log.i("RecordingController", "Session $sessionId stopped. Results: $stopResults")
        } finally {
            _state.value = SessionOrchestrator.State.IDLE
            _currentSessionId.value = null
            sessionRootDir = null
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
            runCatching { entry.recorder.stop() }
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
     */
    private fun createSessionMetadata(sessionDir: File, sessionId: String) {
        try {
            val metadataFile = File(sessionDir, "session_metadata.json")

            // Create metadata using safe JSON construction
            val metadata = try {
                val recordersArray = JSONArray()
                recorders.forEach { recordersArray.put(it.name) }

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
                    .put("session_status", "STARTED")
                    .put("created_at", dateFormatter.format(java.util.Date(sessionStartTimestampMs)))
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
            Log.d("RecordingController", "Session metadata created: ${metadataFile.absolutePath}")
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
}
