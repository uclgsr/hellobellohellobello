package com.yourcompany.sensorspoke.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.network.FileTransferManager
import com.yourcompany.sensorspoke.network.NetworkClient
import com.yourcompany.sensorspoke.utils.PreviewBus
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.*

/**
 * Foreground service that advertises an NSD (Zeroconf) service and hosts a
 * ServerSocket to accept a PC Hub connection and respond to JSON commands.
 *
 * Phase 1 implements only the query_capabilities handshake.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START_RECORDING = "com.yourcompany.sensorspoke.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.yourcompany.sensorspoke.ACTION_STOP_RECORDING"
        const val ACTION_FLASH_SYNC = "com.yourcompany.sensorspoke.ACTION_FLASH_SYNC"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FLASH_TS_NS = "flash_ts_ns"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    private lateinit var networkClient: NetworkClient
    private val clientWriters = Collections.synchronizedList(mutableListOf<BufferedWriter>())
    private var previewListener: ((ByteArray, Long) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        networkClient = NetworkClient(applicationContext)
        startInForeground()
        // Subscribe to preview frames and forward to connected clients
        previewListener = { bytes, ts -> broadcastPreviewFrame(bytes, ts) }
        previewListener?.let { PreviewBus.subscribe(it) }
        scope.launch { startServerAndAdvertise() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep running until explicitly stopped
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            networkClient.unregister()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        previewListener?.let { PreviewBus.unsubscribe(it) }
        previewListener = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "recording_service_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_channel_desc)
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder icon
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private suspend fun startServerAndAdvertise() {
        serverSocket = ServerSocket(0) // auto-assign free port
        serverPort = serverSocket!!.localPort
        networkClient.register("_gsr-controller._tcp.", "SensorSpoke - ${Build.MODEL}", serverPort)

        while (scope.isActive) {
            try {
                val socket = serverSocket!!.accept()
                scope.launch { handleConnection(socket) }
            } catch (_: Exception) {
                // Socket closed or error; exit loop on service stop
                break
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            // Track this client for preview broadcasting
            clientWriters.add(writer)
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val obj = JSONObject(line)
                    val command = obj.optString("command")
                    val id = obj.optInt("id", 0)
                    when (command) {
                        "query_capabilities" -> {
                            val caps = collectCapabilities()
                            val response = JSONObject()
                                .put("ack_id", id)
                                .put("status", "ok")
                                .put("capabilities", caps)
                            safeWrite(writer, response.toString())
                        }

                        "time_sync" -> {
                            // PC sent t0; record t1 on arrival and reply immediately with t1 and t2
                            val t1 = System.nanoTime()
                            val t2 = System.nanoTime()
                            val response = JSONObject()
                                .put("ack_id", id)
                                .put("status", "ok")
                                .put("t1", t1)
                                .put("t2", t2)
                            safeWrite(writer, response.toString())
                        }

                        "start_recording" -> {
                            val sessionId = obj.optString("session_id", "")
                            // Forward to UI via broadcast
                            val intent = Intent(ACTION_START_RECORDING)
                                .putExtra(EXTRA_SESSION_ID, sessionId)
                            sendBroadcast(intent)
                            val response = JSONObject().put("ack_id", id).put("status", "ok")
                            safeWrite(writer, response.toString())
                        }

                        "stop_recording" -> {
                            val intent = Intent(ACTION_STOP_RECORDING)
                            sendBroadcast(intent)
                            val response = JSONObject().put("ack_id", id).put("status", "ok")
                            safeWrite(writer, response.toString())
                        }

                        "flash_sync" -> {
                            val ts = System.nanoTime()
                            val intent = Intent(ACTION_FLASH_SYNC).putExtra(EXTRA_FLASH_TS_NS, ts)
                            sendBroadcast(intent)
                            val response = JSONObject().put("ack_id", id).put("status", "ok").put("ts", ts)
                            safeWrite(writer, response.toString())
                        }

                        "transfer_files" -> {
                            val host = obj.optString("host", "")
                            val port = obj.optInt("port", -1)
                            val sessionId = obj.optString("session_id", "")
                            if (host.isNotEmpty() && port > 0 && sessionId.isNotEmpty()) {
                                // Start transfer asynchronously
                                scope.launch {
                                    runCatching {
                                        val ftm = FileTransferManager(applicationContext)
                                        ftm.transferSession(sessionId, host, port)
                                    }
                                }
                                val response = JSONObject().put("ack_id", id).put("status", "ok")
                                safeWrite(writer, response.toString())
                            } else {
                                val response = JSONObject().put("ack_id", id).put("status", "error")
                                    .put("message", "invalid parameters")
                                safeWrite(writer, response.toString())
                            }
                        }

                        else -> {
                            // ignore unknown commands but acknowledge to keep pipeline moving
                            val response = JSONObject().put("ack_id", id).put("status", "unknown_command")
                            safeWrite(writer, response.toString())
                        }
                    }
                }
            } catch (_: Exception) {
                // swallow errors but keep service alive
            } finally {
                try {
                    writer.flush()
                } catch (_: Exception) {
                }
                clientWriters.remove(writer)
            }
        }
    }

    private fun safeWrite(writer: BufferedWriter, text: String) {
        try {
            synchronized(writer) {
                writer.write(text)
                writer.write("\n")
                writer.flush()
            }
        } catch (_: Exception) {
            // ignore write errors; connection handler will close
        }
    }

    private fun broadcastPreviewFrame(bytes: ByteArray, tsNs: Long) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val obj = JSONObject()
            .put("type", "preview_frame")
            .put("device_id", Build.MODEL ?: "device")
            .put("jpeg_base64", b64)
            .put("ts", tsNs)
        val text = obj.toString()
        val snapshot: List<BufferedWriter> = synchronized(clientWriters) { clientWriters.toList() }
        for (w in snapshot) {
            safeWrite(w, text)
        }
    }

    private fun collectCapabilities(): JSONObject {
        val result = JSONObject()
        result.put("device_model", Build.MODEL ?: "unknown")
        result.put("has_thermal", false)
        val cameras = JSONArray()
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
                val facing = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                cameras.put(JSONObject().put("id", id).put("facing", facing))
            }
        } catch (_: Exception) {
            // ignore camera errors
        }
        result.put("cameras", cameras)
        return result
    }
}
