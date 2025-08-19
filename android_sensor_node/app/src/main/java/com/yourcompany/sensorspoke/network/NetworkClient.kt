package com.yourcompany.sensorspoke.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * NetworkClient encapsulates Android NSD (Zeroconf) service registration
 * and TCP communication for the Sensor Spoke. Supports both service discovery
 * and reliable message transmission to PC Hub.
 */
class NetworkClient(private val context: Context) {
    companion object {
        private const val TAG = "NetworkClient"
        private const val DEFAULT_TIMEOUT_MS = 10000
        private const val CONNECTION_RETRY_DELAY_MS = 1000L
    }

    private var nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    // Connection state
    private val socket = AtomicReference<Socket?>()
    private val isConnected = AtomicBoolean(false)
    private val serverAddress = AtomicReference<InetSocketAddress?>()

    // Connection configuration
    var connectionTimeoutMs: Int = DEFAULT_TIMEOUT_MS
    var autoReconnect: Boolean = true

    /**
     * Register this device as an NSD service for PC Hub discovery.
     */
    fun register(
        type: String,
        name: String,
        port: Int,
    ) {
        val sanitizedType = if (type.endsWith(".local.")) type else "$type.local."
        val info = NsdServiceInfo()
        // Use explicit Java-style setters to avoid Kotlin property mutability issues
        info.serviceType = sanitizedType
        info.serviceName = name
        info.port = port

        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service registered: ${nsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Log.e(TAG, "Service registration failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Log.e(TAG, "Service unregistration failed: $errorCode")
                }
            }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * Unregister the NSD service.
     */
    fun unregister() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering service", e)
            }
        }
        registrationListener = null
    }

    /**
     * Connect to the PC Hub server.
     */
    fun connect(
        host: String,
        port: Int,
    ): Boolean {
        return try {
            disconnect() // Close any existing connection

            val newSocket = Socket()
            val address = InetSocketAddress(host, port)

            Log.i(TAG, "Connecting to $host:$port...")
            newSocket.connect(address, connectionTimeoutMs)

            socket.set(newSocket)
            serverAddress.set(address)
            isConnected.set(true)

            Log.i(TAG, "Successfully connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $host:$port", e)
            isConnected.set(false)
            false
        }
    }

    /**
     * Disconnect from the PC Hub server.
     */
    fun disconnect() {
        isConnected.set(false)
        socket.getAndSet(null)?.let { s ->
            try {
                if (!s.isClosed) {
                    s.close()
                }
                Log.i(TAG, "Disconnected from server")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }

    /**
     * Send a message to the PC Hub server.
     * @param message The message to send (JSON string)
     * @return true if message was sent successfully, false otherwise
     */
    fun sendMessage(message: String): Boolean {
        val currentSocket = socket.get()
        if (currentSocket == null || currentSocket.isClosed || !isConnected.get()) {
            Log.w(TAG, "Cannot send message - not connected")
            return false
        }

        return try {
            val outputStream: OutputStream = currentSocket.getOutputStream()
            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)

            // Send message with newline delimiter (line-based protocol)
            outputStream.write(messageBytes)
            outputStream.write('\n'.code)
            outputStream.flush()

            Log.d(TAG, "Message sent successfully: ${message.take(100)}...")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send message", e)
            markConnectionLost()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending message", e)
            false
        }
    }

    /**
     * Attempt to reconnect to the previously connected server.
     * @return true if reconnection succeeded, false otherwise
     */
    fun reconnect(): Boolean {
        val address = serverAddress.get()
        if (address == null) {
            Log.w(TAG, "Cannot reconnect - no previous server address")
            return false
        }

        return connect(address.hostString, address.port)
    }

    /**
     * Check if currently connected to a server.
     */
    fun isConnected(): Boolean {
        val currentSocket = socket.get()
        return isConnected.get() && currentSocket != null &&
            !currentSocket.isClosed && currentSocket.isConnected
    }

    /**
     * Get current server address (if connected).
     */
    fun getServerAddress(): String? {
        return serverAddress.get()?.let { "${it.hostString}:${it.port}" }
    }

    /**
     * Mark connection as lost and trigger cleanup.
     */
    private fun markConnectionLost() {
        if (isConnected.getAndSet(false)) {
            Log.w(TAG, "Connection marked as lost")
            // Don't close socket immediately - let reconnect logic handle it
        }
    }

    /**
     * Get connection status summary for debugging.
     */
    fun getConnectionStatus(): Map<String, Any> {
        return mapOf(
            "connected" to isConnected(),
            "server_address" to (getServerAddress() ?: "none"),
            "socket_closed" to (socket.get()?.isClosed ?: true),
            "auto_reconnect" to autoReconnect,
            "timeout_ms" to connectionTimeoutMs,
        )
    }
}
