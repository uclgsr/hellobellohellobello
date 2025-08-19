package com.yourcompany.sensorspoke.network

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for HeartbeatManager functionality.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatManagerTest {

    private lateinit var context: Context
    private lateinit var mockNetworkClient: NetworkClient
    private lateinit var heartbeatManager: HeartbeatManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockNetworkClient = NetworkClient(context)
        heartbeatManager = HeartbeatManager(
            context = context,
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
}