package com.yourcompany.sensorspoke.sensors.gsr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests for ShimmerManager to validate device lifecycle management.
 * These tests ensure the manager properly handles connection states and device information.
 */
@RunWith(AndroidJUnit4::class)
class ShimmerManagerTest {

    private lateinit var context: Context
    private lateinit var shimmerManager: ShimmerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shimmerManager = ShimmerManager(context)
    }

    @After
    fun tearDown() {
        shimmerManager.cleanup()
    }

    @Test
    fun testInitialization() = runTest {
        // Test that initialization sets up the manager correctly
        val result = shimmerManager.initialize()
        
        assertTrue("ShimmerManager should initialize successfully", result)
        assertEquals(
            "Connection state should be DISCONNECTED after initialization",
            ShimmerManager.ConnectionState.DISCONNECTED,
            shimmerManager.connectionState.first()
        )
        assertNotNull(
            "Device info should be set after initialization",
            shimmerManager.deviceInfo.first()
        )
    }

    @Test
    fun testDeviceInfoIsSimulated() = runTest {
        shimmerManager.initialize()
        
        val deviceInfo = shimmerManager.deviceInfo.first()
        assertNotNull("Device info should not be null", deviceInfo)
        assertTrue("Device should be marked as simulated", deviceInfo!!.isSimulated)
        assertEquals("Device ID should be SIM_001", "SIM_001", deviceInfo.deviceId)
        assertEquals("Device name should be Simulated Shimmer", "Simulated Shimmer", deviceInfo.name)
    }

    @Test
    fun testConnectionFlow() = runTest {
        shimmerManager.initialize()
        
        // Test scanning
        val scanResult = shimmerManager.startScanning()
        assertTrue("Scanning should start successfully", scanResult)
        
        // Test connection
        val connectResult = shimmerManager.connect("SIM_001")
        assertTrue("Connection should succeed", connectResult)
        assertEquals(
            "Connection state should be CONNECTED",
            ShimmerManager.ConnectionState.CONNECTED,
            shimmerManager.connectionState.first()
        )
        assertTrue("isConnected() should return true", shimmerManager.isConnected())
    }

    @Test
    fun testDeviceConfiguration() = runTest {
        shimmerManager.initialize()
        shimmerManager.connect("SIM_001")
        
        val configResult = shimmerManager.configureDevice(256.0)
        assertTrue("Device configuration should succeed", configResult)
        assertEquals(
            "Data rate should be updated to 256.0",
            256.0,
            shimmerManager.dataRate.first(),
            0.1
        )
    }

    @Test
    fun testConfigurationWithoutConnection() = runTest {
        shimmerManager.initialize()
        
        val configResult = shimmerManager.configureDevice(128.0)
        assertFalse("Configuration should fail when not connected", configResult)
    }

    @Test
    fun testDisconnection() = runTest {
        shimmerManager.initialize()
        shimmerManager.connect("SIM_001")
        
        shimmerManager.disconnect()
        assertEquals(
            "Connection state should be DISCONNECTED after disconnect",
            ShimmerManager.ConnectionState.DISCONNECTED,
            shimmerManager.connectionState.first()
        )
        assertFalse("isConnected() should return false", shimmerManager.isConnected())
        assertEquals("Data rate should be reset to 0", 0.0, shimmerManager.dataRate.first(), 0.1)
    }

    @Test
    fun testBatteryLevel() = runTest {
        shimmerManager.initialize()
        
        val batteryLevel = shimmerManager.getBatteryLevel()
        assertEquals("Battery level should be 85", 85, batteryLevel)
    }

    @Test
    fun testOperationsWithoutInitialization() = runTest {
        // Test operations without initialization
        val scanResult = shimmerManager.startScanning()
        assertFalse("Scanning should fail without initialization", scanResult)
        
        val connectResult = shimmerManager.connect("SIM_001")
        assertFalse("Connection should fail without initialization", connectResult)
    }

    @Test
    fun testCleanup() = runTest {
        shimmerManager.initialize()
        shimmerManager.connect("SIM_001")
        
        shimmerManager.cleanup()
        
        assertEquals(
            "Connection state should be DISCONNECTED after cleanup",
            ShimmerManager.ConnectionState.DISCONNECTED,
            shimmerManager.connectionState.first()
        )
        assertFalse("isConnected() should return false after cleanup", shimmerManager.isConnected())
    }
}