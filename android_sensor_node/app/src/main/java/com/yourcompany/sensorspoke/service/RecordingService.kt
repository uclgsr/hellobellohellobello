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
import androidx.core.app.NotificationCompat
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that advertises an NSD (Zeroconf) service and hosts a
 * ServerSocket to accept a PC Hub connection and respond to JSON commands.
 *
 * Phase 1 implements only the query_capabilities handshake.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    private lateinit var networkClient: NetworkClient

    override fun onCreate() {
        super.onCreate()
        networkClient = NetworkClient(applicationContext)
        startInForeground()
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
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "recording_service_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            try {
                val line = reader.readLine() ?: return
                val obj = JSONObject(line)
                val command = obj.optString("command")
                if (command == "query_capabilities") {
                    val ackId = obj.optInt("id", 1)
                    val caps = collectCapabilities()
                    val response = JSONObject()
                        .put("ack_id", ackId)
                        .put("status", "ok")
                        .put("capabilities", caps)
                    writer.write(response.toString())
                    writer.write("\n")
                    writer.flush()
                }
            } catch (_: Exception) {
                // swallow errors in Phase 1 prototype
            } finally {
                try { writer.flush() } catch (_: Exception) {}
            }
        }
    }

    private fun collectCapabilities(): JSONObject {
        val result = JSONObject()
        result.put("device_model", Build.MODEL ?: "unknown")
        result.put("has_thermal", false)
        val cameras = JSONArray()
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
