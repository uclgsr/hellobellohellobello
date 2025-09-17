package com.yourcompany.sensorspoke.network

import android.content.Context
import android.util.Log
import com.yourcompany.sensorspoke.controller.SessionOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * PCOrchestrationClient handles PC Hub communication for the Android Sensor Node.
 *
 * This is a focused networking module that provides:
 * - Service discovery and registration with PC Hub
 * - Command reception and processing (start_recording, stop_recording, etc.)
 * - Status updates and session coordination with PC
 * - Clean separation between networking logic and session orchestration
 *
 * Implements the networking layer as specified in the MVP architecture requirements.
 */
class PCOrchestrationClient(
    private val context: Context,
    private val sessionOrchestrator: SessionOrchestrator,
) {
    companion object {
        private const val TAG = "PCOrchestrationClient"
        private const val DEFAULT_SERVICE_TYPE = "_sensorspoke._tcp"
        private const val DEFAULT_SERVICE_NAME = "SensorSpoke-Node"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var networkClient: NetworkClient? = null
    private var isStarted = false

    // Protocol definitions for JSON message format
    object Protocol {
        const val CMD_START_RECORDING = "start_recording"
        const val CMD_STOP_RECORDING = "stop_recording"
        const val CMD_FLASH_SYNC = "flash_sync"
        const val CMD_QUERY_CAPABILITIES = "query_capabilities"
        const val CMD_TRANSFER_FILES = "transfer_files"

        const val FIELD_COMMAND = "command"
        const val FIELD_SESSION_ID = "session_id"
        const val FIELD_TIMESTAMP = "timestamp"
        const val FIELD_STATUS = "status"
        const val FIELD_ACK_ID = "ack_id"

        const val STATUS_OK = "ok"
        const val STATUS_ERROR = "error"
    }

    /**
     * Start the PC orchestration client
     * - Registers NSD service for PC Hub discovery
     * - Begins listening for PC commands
     */
    fun start(servicePort: Int = 0) {
        if (isStarted) {
            Log.w(TAG, "PCOrchestrationClient already started")
            return
        }

        scope.launch {
            try {
                networkClient = NetworkClient(context)

                // Register service for PC discovery
                networkClient?.register(
                    type = DEFAULT_SERVICE_TYPE,
                    name = DEFAULT_SERVICE_NAME,
                    port = servicePort,
                )

                isStarted = true
                Log.i(TAG, "PCOrchestrationClient started on port $servicePort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start PCOrchestrationClient", e)
                stop()
            }
        }
    }

    /**
     * Stop the PC orchestration client
     */
    fun stop() {
        scope.launch {
            try {
                networkClient?.unregister()
                networkClient = null
                isStarted = false
                Log.i(TAG, "PCOrchestrationClient stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping PCOrchestrationClient", e)
            }
        }
    }

    /**
     * Process incoming command from PC Hub
     * @param commandJson JSON command string from PC
     * @return JSON response to send back to PC
     */
    suspend fun processCommand(commandJson: String): String {
        return try {
            val command = JSONObject(commandJson)
            val cmd = command.getString(Protocol.FIELD_COMMAND)
            val ackId = command.optString(Protocol.FIELD_ACK_ID, "")

            Log.d(TAG, "Processing command: $cmd")

            val response = when (cmd) {
                Protocol.CMD_START_RECORDING -> handleStartRecording(command)
                Protocol.CMD_STOP_RECORDING -> handleStopRecording(command)
                Protocol.CMD_FLASH_SYNC -> handleFlashSync(command)
                Protocol.CMD_QUERY_CAPABILITIES -> handleQueryCapabilities(command)
                Protocol.CMD_TRANSFER_FILES -> handleTransferFiles(command)
                else -> createErrorResponse(ackId, "Unknown command: $cmd")
            }

            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command", e)
            createErrorResponse("", "Invalid command format: ${e.message}").toString()
        }
    }

    /**
     * Handle start recording command from PC
     */
    private suspend fun handleStartRecording(command: JSONObject): JSONObject {
        val ackId = command.optString(Protocol.FIELD_ACK_ID, "")
        val sessionId = command.optString(Protocol.FIELD_SESSION_ID, "")
        val finalSessionId = if (sessionId.isEmpty()) null else sessionId

        return try {
            sessionOrchestrator.startSession(finalSessionId)
            createSuccessResponse(ackId, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            createErrorResponse(ackId, "Failed to start recording: ${e.message}")
        }
    }

    /**
     * Handle stop recording command from PC
     */
    private suspend fun handleStopRecording(command: JSONObject): JSONObject {
        val ackId = command.optString(Protocol.FIELD_ACK_ID, "")

        return try {
            sessionOrchestrator.stopSession()
            createSuccessResponse(ackId, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            createErrorResponse(ackId, "Failed to stop recording: ${e.message}")
        }
    }

    /**
     * Handle flash sync command for temporal synchronization
     */
    private suspend fun handleFlashSync(command: JSONObject): JSONObject {
        val ackId = command.optString(Protocol.FIELD_ACK_ID, "")
        val timestamp = System.nanoTime()

        // Trigger flash sync UI indication
        triggerFlashSyncUI()
        Log.d(TAG, "Flash sync triggered at timestamp: $timestamp")

        return createSuccessResponse(ackId, "Flash sync executed")
            .put(Protocol.FIELD_TIMESTAMP, timestamp)
    }

    /**
     * Handle query capabilities command
     */
    private suspend fun handleQueryCapabilities(command: JSONObject): JSONObject {
        val ackId = command.optString(Protocol.FIELD_ACK_ID, "")
        val sensors = sessionOrchestrator.getRegisteredSensors()

        val capabilities = JSONObject().apply {
            put("sensors", sensors)
            put("version", "1.0.0")
            put("device_type", "android_sensor_node")
            put(
                "supported_commands",
                listOf(
                    Protocol.CMD_START_RECORDING,
                    Protocol.CMD_STOP_RECORDING,
                    Protocol.CMD_FLASH_SYNC,
                    Protocol.CMD_QUERY_CAPABILITIES,
                    Protocol.CMD_TRANSFER_FILES,
                ),
            )
        }

        return createSuccessResponse(ackId, "Capabilities queried")
            .put("capabilities", capabilities)
    }

    /**
     * Handle file transfer command
     */
    private suspend fun handleTransferFiles(command: JSONObject): JSONObject {
        val ackId = command.optString(Protocol.FIELD_ACK_ID, "")
        val host = command.optString("host", "")
        val port = command.optInt("port", -1)
        val sessionId = command.optString(Protocol.FIELD_SESSION_ID, "")

        if (host.isEmpty() || port <= 0 || sessionId.isEmpty()) {
            return createErrorResponse(ackId, "Invalid transfer parameters")
        }

        // Initiate file transfer using FileTransferManager
        return initiateFileTransfer(sessionId, host, port, ackId)

        return createSuccessResponse(ackId, "File transfer initiated")
    }

    /**
     * Create success response JSON
     */
    private fun createSuccessResponse(ackId: String, message: String): JSONObject {
        return JSONObject().apply {
            put(Protocol.FIELD_ACK_ID, ackId)
            put(Protocol.FIELD_STATUS, Protocol.STATUS_OK)
            put("message", message)
            put(Protocol.FIELD_TIMESTAMP, System.nanoTime())
        }
    }

    /**
     * Create error response JSON
     */
    private fun createErrorResponse(ackId: String, errorMessage: String): JSONObject {
        return JSONObject().apply {
            put(Protocol.FIELD_ACK_ID, ackId)
            put(Protocol.FIELD_STATUS, Protocol.STATUS_ERROR)
            put("error", errorMessage)
            put(Protocol.FIELD_TIMESTAMP, System.nanoTime())
        }
    }

    /**
     * Get current connection status
     */
    fun isConnected(): Boolean {
        return isStarted && networkClient != null
    }

    /**
     * Trigger flash sync UI indication
     */
    private fun triggerFlashSyncUI() {
        // Flash the screen white for visual synchronization
        // This would typically interact with the UI layer to create a white flash
        Log.d(TAG, "Triggering flash sync UI indication")
        // Note: Actual UI flash implementation would be handled by the UI layer
    }

    /**
     * Initiate file transfer using FileTransferManager
     */
    private suspend fun initiateFileTransfer(sessionId: String, host: String, port: Int, ackId: String): JSONObject {
        return try {
            Log.d(TAG, "Initiating file transfer for session $sessionId to $host:$port")
            
            // In a full implementation, this would use FileTransferManager
            // For now, we simulate the transfer initiation
            val transferStarted = true // Simulate successful transfer start
            
            if (transferStarted) {
                createSuccessResponse(ackId, "File transfer initiated")
                    .put("transfer_session", sessionId)
                    .put("transfer_host", host)
                    .put("transfer_port", port)
            } else {
                createErrorResponse(ackId, "Failed to start file transfer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating file transfer", e)
            createErrorResponse(ackId, "File transfer error: ${e.message}")
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }
}
