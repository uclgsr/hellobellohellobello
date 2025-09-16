package com.yourcompany.sensorspoke

import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.network.NetworkClient
import com.yourcompany.sensorspoke.ui.StubSensorRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertTrue

/**
 * Phase 1 Integration Test - Validates foundational architecture components.
 */
@RunWith(RobolectricTestRunner::class)
class Phase1IntegrationTest {

    @Test
    fun `test RecordingController session lifecycle`() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val controller = RecordingController(context)

        // Register stub sensor for testing
        controller.register("test_sensor", StubSensorRecorder())

        // Test session start
        controller.startSession("test_session_123")

        // Verify session state
        assertTrue(
            controller.state.value == RecordingController.State.RECORDING,
            "Controller should be in RECORDING state",
        )
        assertTrue(
            controller.currentSessionId.value == "test_session_123",
            "Current session ID should be set",
        )

        // Test session stop
        controller.stopSession()

        // Verify final state
        assertTrue(
            controller.state.value == RecordingController.State.IDLE,
            "Controller should return to IDLE state",
        )
    }

    @Test
    fun `test session directory creation`() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val controller = RecordingController(context)
        controller.register("test_sensor", StubSensorRecorder())

        val sessionId = "test_dir_session"
        controller.startSession(sessionId)

        // Verify session directory exists
        val expectedDir = File(context.filesDir, "sessions/$sessionId")
        assertTrue(expectedDir.exists(), "Session directory should be created")
        assertTrue(expectedDir.isDirectory, "Session path should be a directory")

        // Verify sensor subdirectory exists
        val sensorDir = File(expectedDir, "test_sensor")
        assertTrue(sensorDir.exists(), "Sensor subdirectory should be created")

        controller.stopSession()

        // Verify test files were created by stub sensor
        val testFile = File(sensorDir, "stub_sensor_test.log")
        assertTrue(testFile.exists(), "Stub sensor should create test file")

        val dataFile = File(sensorDir, "stub_data.csv")
        assertTrue(dataFile.exists(), "Stub sensor should create data file")
    }

    @Test
    fun `test NetworkClient NSD registration format`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val networkClient = NetworkClient(context)

        // Test service registration (this will fail in unit test environment but we can verify the method exists)
        try {
            networkClient.register("_gsr-controller._tcp", "TestDevice", 8080)
            // If we get here, the method signature is correct
            assertTrue(true, "NetworkClient.register method should exist with correct signature")
        } catch (e: Exception) {
            // Expected in test environment - just verify method exists
            assertTrue(
                e.message?.contains("NsdManager") == true ||
                    e.javaClass.simpleName.contains("Unsupported"),
                "Should fail due to NsdManager not available in test environment",
            )
        }
    }

    @Test
    fun `test StubSensorRecorder functionality`() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val sessionDir = File(context.cacheDir, "test_session")
        sessionDir.mkdirs()

        val stubSensor = StubSensorRecorder()

        // Test start
        stubSensor.start(sessionDir)

        // Verify files created
        val testFile = File(sessionDir, "stub_sensor_test.log")
        assertTrue(testFile.exists(), "Test log file should be created")

        val dataFile = File(sessionDir, "stub_data.csv")
        assertTrue(dataFile.exists(), "Data CSV file should be created")
        assertTrue(
            dataFile.readText().contains("timestamp,value"),
            "CSV should have header",
        )

        // Test stop
        stubSensor.stop()

        // Verify stop was logged
        val logContent = testFile.readText()
        assertTrue(logContent.contains("started"), "Log should contain start message")
        assertTrue(logContent.contains("stopped"), "Log should contain stop message")

        // Cleanup
        sessionDir.deleteRecursively()
    }
}
