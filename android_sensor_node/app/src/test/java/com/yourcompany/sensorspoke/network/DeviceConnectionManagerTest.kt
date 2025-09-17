package com.yourcompany.sensorspoke.network

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
 * Unit tests for DeviceConnectionManager to validate unified device state management.
 * These tests ensure proper coordination of connection states across all sensor types.
 */
@RunWith(AndroidJUnit4::class)
class DeviceConnectionManagerTest {

    private lateinit var context: Context
    private lateinit var deviceManager: DeviceConnectionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deviceManager = DeviceConnectionManager(context)
    }

    @After
    fun tearDown() {
        deviceManager.cleanup()
    }

    @Test
    fun testInitialState() = runTest {
        // Test that all devices start disconnected
        assertEquals(
            "Shimmer should start disconnected",
            DeviceConnectionManager.DeviceState.DISCONNECTED,
            deviceManager.shimmerState.first()
        )
        assertEquals(
            "RGB camera should start disconnected",
            DeviceConnectionManager.DeviceState.DISCONNECTED,
            deviceManager.rgbCameraState.first()
        )
        assertEquals(
            "Thermal camera should start disconnected",
            DeviceConnectionManager.DeviceState.DISCONNECTED,
            deviceManager.thermalCameraState.first()
        )
        assertEquals(
            "Overall state should be NOT_READY",
            DeviceConnectionManager.OverallSystemState.NOT_READY,
            deviceManager.overallState.first()
        )
        assertEquals("Connected device count should be 0", 0, deviceManager.getConnectedDeviceCount())
    }

    @Test
    fun testShimmerStateUpdate() = runTest {
        val deviceDetails = DeviceConnectionManager.DeviceDetails(
            deviceType = "Shimmer3 GSR+",
            deviceName = "Test Shimmer",
            connectionState = DeviceConnectionManager.DeviceState.CONNECTED,
            batteryLevel = 80,
            dataRate = 128.0,
            isRequired = true
        )

        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED, deviceDetails)

        assertEquals(
            "Shimmer state should be updated",
            DeviceConnectionManager.DeviceState.CONNECTED,
            deviceManager.shimmerState.first()
        )
        assertEquals("Connected device count should be 1", 1, deviceManager.getConnectedDeviceCount())
        
        val details = deviceManager.deviceDetails.first()
        assertTrue("Device details should contain shimmer", details.containsKey("shimmer"))
        assertEquals("Shimmer details should match", deviceDetails, details["shimmer"])
    }

    @Test
    fun testMultipleDeviceConnections() = runTest {
        // Connect multiple devices
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateThermalCameraState(DeviceConnectionManager.DeviceState.CONNECTED)

        assertEquals("Connected device count should be 3", 3, deviceManager.getConnectedDeviceCount())
        assertEquals(
            "Overall state should be PARTIALLY_READY or READY",
            true,
            deviceManager.overallState.first() in listOf(
                DeviceConnectionManager.OverallSystemState.PARTIALLY_READY,
                DeviceConnectionManager.OverallSystemState.READY
            )
        )
    }

    @Test
    fun testAllDevicesConnected() = runTest {
        // Connect all devices
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateThermalCameraState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateAudioDeviceState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateNetworkState(DeviceConnectionManager.DeviceState.CONNECTED)

        assertEquals("Connected device count should be 5", 5, deviceManager.getConnectedDeviceCount())
        assertEquals(
            "Overall state should be READY",
            DeviceConnectionManager.OverallSystemState.READY,
            deviceManager.overallState.first()
        )
        assertTrue("System should be ready for recording", deviceManager.isReadyForRecording())
    }

    @Test
    fun testErrorStateHandling() = runTest {
        // Set one device to error state
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.ERROR)

        assertEquals(
            "Overall state should be ERROR when any device has error",
            DeviceConnectionManager.OverallSystemState.ERROR,
            deviceManager.overallState.first()
        )
        assertFalse("System should not be ready for recording with errors", deviceManager.isReadyForRecording())
    }

    @Test
    fun testPartiallyReadyState() = runTest {
        // Connect some but not all devices
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.CONNECTED)
        // Leave other devices disconnected

        assertEquals("Connected device count should be 2", 2, deviceManager.getConnectedDeviceCount())
        assertEquals(
            "Overall state should be PARTIALLY_READY",
            DeviceConnectionManager.OverallSystemState.PARTIALLY_READY,
            deviceManager.overallState.first()
        )
        assertTrue("System should be ready for recording in partial state", deviceManager.isReadyForRecording())
    }

    @Test
    fun testDeviceStateSummary() = runTest {
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.CONNECTING)
        deviceManager.updateThermalCameraState(DeviceConnectionManager.DeviceState.ERROR)

        val summary = deviceManager.getDeviceStateSummary()
        
        assertEquals("Summary should contain all devices", 5, summary.size)
        assertEquals(
            "Shimmer state should be correct",
            DeviceConnectionManager.DeviceState.CONNECTED,
            summary["shimmer"]
        )
        assertEquals(
            "RGB camera state should be correct",
            DeviceConnectionManager.DeviceState.CONNECTING,
            summary["rgb_camera"]
        )
        assertEquals(
            "Thermal camera state should be correct",
            DeviceConnectionManager.DeviceState.ERROR,
            summary["thermal_camera"]
        )
    }

    @Test
    fun testResetAllStates() = runTest {
        // Connect some devices first
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.CONNECTED)

        // Reset all states
        deviceManager.resetAllStates()

        assertEquals("Connected device count should be 0 after reset", 0, deviceManager.getConnectedDeviceCount())
        assertEquals(
            "Overall state should be NOT_READY after reset",
            DeviceConnectionManager.OverallSystemState.NOT_READY,
            deviceManager.overallState.first()
        )
        assertTrue("Device details should be empty after reset", deviceManager.deviceDetails.first().isEmpty())
    }

    @Test
    fun testDeviceDetailsWithBatteryAndDataRate() = runTest {
        val deviceDetails = DeviceConnectionManager.DeviceDetails(
            deviceType = "Shimmer3 GSR+",
            deviceName = "Test Shimmer",
            connectionState = DeviceConnectionManager.DeviceState.CONNECTED,
            batteryLevel = 75,
            dataRate = 256.0,
            isRequired = true
        )

        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED, deviceDetails)

        val storedDetails = deviceManager.deviceDetails.first()["shimmer"]
        assertNotNull("Device details should be stored", storedDetails)
        assertEquals("Battery level should match", 75, storedDetails!!.batteryLevel)
        assertEquals("Data rate should match", 256.0, storedDetails.dataRate!!, 0.1)
        assertTrue("Device should be marked as required", storedDetails.isRequired)
    }

    @Test
    fun testCleanup() = runTest {
        // Connect some devices
        deviceManager.updateShimmerState(DeviceConnectionManager.DeviceState.CONNECTED)
        deviceManager.updateRgbCameraState(DeviceConnectionManager.DeviceState.CONNECTED)

        // Cleanup
        deviceManager.cleanup()

        assertEquals("Connected device count should be 0 after cleanup", 0, deviceManager.getConnectedDeviceCount())
        assertEquals(
            "Overall state should be NOT_READY after cleanup",
            DeviceConnectionManager.OverallSystemState.NOT_READY,
            deviceManager.overallState.first()
        )
    }
}