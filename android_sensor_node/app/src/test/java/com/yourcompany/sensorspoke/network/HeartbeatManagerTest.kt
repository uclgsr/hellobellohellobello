package com.yourcompany.sensorspoke.network

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for HeartbeatManager functionality.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatManagerTest {

    private lateinit var mockNetworkClient: MockNetworkClient
    private lateinit var heartbeatManager: HeartbeatManager

    @Before
    fun setUp() {
        mockNetworkClient = MockNetworkClient()
        heartbeatManager = HeartbeatManager(
            deviceId = "test_device",
            networkClient = mockNetworkClient,
            heartbeatIntervalMs = 100L, // Fast for testing
            maxReconnectAttempts = 3,
            reconnectBackoffMs = 50L
        )
    }

    @After
    fun tearDown() {
        heartbeatManager.stopHeartbeats()
    }

    @Test
    fun testInitialState() {
        assertFalse("Manager should not be running initially", heartbeatManager.isConnectionHealthy())
        assertEquals("No reconnect attempts initially", 0, heartbeatManager.getReconnectAttempts())
        assertEquals("No last heartbeat initially", -1, heartbeatManager.getTimeSinceLastHeartbeat())
    }

    @Test
    fun testStartStopHeartbeats() {
        // Start heartbeats
        heartbeatManager.startHeartbeats()
        
        // Advance looper to trigger heartbeat
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        
        assertTrue("At least one message should be sent", mockNetworkClient.messagesSent.size >= 1)
        
        // Verify heartbeat message format
        val message = mockNetworkClient.messagesSent.first()
        val json = JSONObject(message)
        
        assertEquals("Message version should be 1", 1, json.getInt("v"))
        assertEquals("Message type should be heartbeat", "heartbeat", json.getString("type"))
        assertEquals("Device ID should match", "test_device", json.getString("device_id"))
        assertTrue("Should have timestamp", json.has("timestamp_ns"))
        assertTrue("Should have metadata", json.has("metadata"))
        
        // Stop heartbeats
        heartbeatManager.stopHeartbeats()
        val messageCountAfterStop = mockNetworkClient.messagesSent.size
        
        // Advance looper again - no new messages should be sent
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("No new messages after stop", messageCountAfterStop, mockNetworkClient.messagesSent.size)
    }

    @Test
    fun testSendImmediateHeartbeat() {
        heartbeatManager.startHeartbeats()
        
        val metadata = mapOf("test_key" to "test_value")
        heartbeatManager.sendImmediateHeartbeat(metadata)
        
        assertTrue("Should have sent at least one message", mockNetworkClient.messagesSent.isNotEmpty())
        
        val message = mockNetworkClient.messagesSent.last()
        val json = JSONObject(message)
        val metadataJson = json.getJSONObject("metadata")
        
        assertEquals("Metadata should be included", "test_value", metadataJson.getString("test_key"))
    }

    @Test
    fun testConnectionLostAndRestored() {
        val connectionLostLatch = CountDownLatch(1)
        val connectionRestoredLatch = CountDownLatch(1)
        
        heartbeatManager.onConnectionLost = { connectionLostLatch.countDown() }
        heartbeatManager.onConnectionRestored = { connectionRestoredLatch.countDown() }
        
        heartbeatManager.startHeartbeats()
        
        // Initially connected
        assertTrue("Should be connected initially", heartbeatManager.isConnectionHealthy())
        
        // Mark connection as lost
        heartbeatManager.markConnectionLost()
        assertFalse("Should be disconnected", heartbeatManager.isConnectionHealthy())
        assertTrue("Connection lost callback should be triggered", 
            connectionLostLatch.await(1, TimeUnit.SECONDS))
        
        // Mark connection as restored
        heartbeatManager.markConnectionRestored()
        assertTrue("Should be connected again", heartbeatManager.isConnectionHealthy())
        assertEquals("Reconnect attempts should be reset", 0, heartbeatManager.getReconnectAttempts())
        assertTrue("Connection restored callback should be triggered", 
            connectionRestoredLatch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun testReconnectionAttempts() {
        val maxAttemptsLatch = CountDownLatch(1)
        heartbeatManager.onMaxReconnectAttemptsReached = { maxAttemptsLatch.countDown() }
        
        heartbeatManager.startHeartbeats()
        mockNetworkClient.shouldFailSend = true // Make send operations fail
        
        // Trigger connection loss
        heartbeatManager.markConnectionLost()
        
        // Allow reconnection attempts to proceed
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        
        assertTrue("Max reconnect attempts should be reached", 
            maxAttemptsLatch.await(2, TimeUnit.SECONDS))
        assertTrue("Should have attempted reconnections", heartbeatManager.getReconnectAttempts() > 0)
    }

    @Test
    fun testHeartbeatFailureTriggesConnectionLoss() {
        val connectionLostLatch = CountDownLatch(1)
        heartbeatManager.onConnectionLost = { connectionLostLatch.countDown() }
        
        heartbeatManager.startHeartbeats()
        
        // Make network client fail
        mockNetworkClient.shouldFailSend = true
        
        // Advance looper to trigger heartbeat attempt
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        
        assertTrue("Connection lost should be triggered on send failure", 
            connectionLostLatch.await(1, TimeUnit.SECONDS))
        assertFalse("Should be marked as disconnected", heartbeatManager.isConnectionHealthy())
    }

    @Test
    fun testStatusSummary() {
        heartbeatManager.startHeartbeats()
        
        val status = heartbeatManager.getStatusSummary()
        
        assertEquals("Device ID should match", "test_device", status["device_id"])
        assertTrue("Should be running", status["is_running"] as Boolean)
        assertTrue("Should be connected initially", status["is_connected"] as Boolean)
        assertEquals("No reconnect attempts initially", 0, status["reconnect_attempts"])
        assertEquals("Heartbeat interval should match", 100L, status["heartbeat_interval_ms"])
    }

    @Test
    fun testMultipleStartCallsDoNotCreateDuplicates() {
        heartbeatManager.startHeartbeats()
        heartbeatManager.startHeartbeats() // Should not create duplicate schedulers
        
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        
        // Should not crash or create excessive messages
        assertTrue("Should have sent messages", mockNetworkClient.messagesSent.isNotEmpty())
        // Exact count depends on timing, but should be reasonable
        assertTrue("Should not have excessive messages", mockNetworkClient.messagesSent.size < 10)
    }

    @Test
    fun testHeartbeatMessageStructure() {
        heartbeatManager.startHeartbeats()
        heartbeatManager.sendImmediateHeartbeat(mapOf("custom_field" to 42))
        
        assertFalse("Should have sent messages", mockNetworkClient.messagesSent.isEmpty())
        
        val message = mockNetworkClient.messagesSent.last()
        val json = JSONObject(message)
        
        // Verify required fields
        assertTrue("Should have version field", json.has("v"))
        assertTrue("Should have type field", json.has("type"))
        assertTrue("Should have device_id field", json.has("device_id"))
        assertTrue("Should have timestamp_ns field", json.has("timestamp_ns"))
        assertTrue("Should have metadata field", json.has("metadata"))
        
        // Verify metadata structure
        val metadata = json.getJSONObject("metadata")
        assertTrue("Should have battery_level", metadata.has("battery_level"))
        assertTrue("Should have recording_active", metadata.has("recording_active"))
        assertTrue("Should have storage_available_mb", metadata.has("storage_available_mb"))
        assertTrue("Should have uptime_ms", metadata.has("uptime_ms"))
        assertTrue("Should have custom field", metadata.has("custom_field"))
        assertEquals("Custom field should have correct value", 42, metadata.getInt("custom_field"))
    }

    /**
     * Mock NetworkClient for testing.
     */
    private class MockNetworkClient : NetworkClient {
        val messagesSent = mutableListOf<String>()
        var shouldFailSend = false
        var shouldFailReconnect = true

        override fun sendMessage(message: String): Boolean {
            return if (shouldFailSend) {
                false
            } else {
                messagesSent.add(message)
                true
            }
        }

        override fun reconnect(): Boolean {
            return !shouldFailReconnect
        }

        override fun isConnected(): Boolean {
            return !shouldFailSend
        }

        override fun disconnect() {
            // No-op for testing
        }
    }

    /**
     * Interface for NetworkClient to enable testing.
     */
    interface NetworkClient {
        fun sendMessage(message: String): Boolean
        fun reconnect(): Boolean
        fun isConnected(): Boolean
        fun disconnect()
    }
}