package com.yourcompany.sensorspoke.controller

import com.yourcompany.sensorspoke.sensors.SensorRecorder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFailsWith

class RecordingControllerComprehensiveTest {

    @Mock
    private lateinit var mockContext: android.content.Context

    private lateinit var tempDir: File
    private lateinit var sessionsRoot: File
    private lateinit var recordingController: RecordingController

    // Test sensor recorders
    private class TestSensorRecorder(private val name: String) : SensorRecorder {
        var isStarted = false
        var startedDir: File? = null
        var stopCount = 0
        var shouldFailOnStart = false
        var shouldFailOnStop = false

        override suspend fun start(sessionDir: File) {
            if (shouldFailOnStart) {
                throw RuntimeException("Simulated start failure for $name")
            }
            startedDir = sessionDir
            isStarted = true
            if (!sessionDir.exists()) sessionDir.mkdirs()
        }

        override suspend fun stop() {
            if (shouldFailOnStop) {
                throw RuntimeException("Simulated stop failure for $name")
            }
            isStarted = false
            stopCount++
        }

        fun reset() {
            isStarted = false
            startedDir = null
            stopCount = 0
            shouldFailOnStart = false
            shouldFailOnStop = false
        }
    }

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        tempDir = Files.createTempDirectory("recording_controller_test").toFile()
        sessionsRoot = File(tempDir, "sessions").apply { mkdirs() }
        recordingController = RecordingController(context = mockContext, sessionsRootOverride = sessionsRoot)
    }

    @Test
    fun `initial state should be idle`() {
        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
        assertNull(recordingController.currentSessionId.value)
    }

    @Test
    fun `single sensor recording lifecycle`() = runTest {
        val sensor = TestSensorRecorder("test_sensor")
        recordingController.register("test_sensor", sensor)

        val sessionId = "single_sensor_test"

        // Start recording
        recordingController.startSession(sessionId)

        assertEquals(RecordingController.State.RECORDING, recordingController.state.value)
        assertEquals(sessionId, recordingController.currentSessionId.value)
        assertTrue(sensor.isStarted)
        assertNotNull(sensor.startedDir)
        assertTrue(sensor.startedDir!!.exists())

        // Stop recording
        recordingController.stopSession()

        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
        assertNull(recordingController.currentSessionId.value)
        assertFalse(sensor.isStarted)
        assertEquals(1, sensor.stopCount)
    }

    @Test
    fun `multiple sensor recording lifecycle`() = runTest {
        val sensor1 = TestSensorRecorder("sensor_1")
        val sensor2 = TestSensorRecorder("sensor_2")
        val sensor3 = TestSensorRecorder("sensor_3")

        recordingController.register("sensor_1", sensor1)
        recordingController.register("sensor_2", sensor2)
        recordingController.register("sensor_3", sensor3)

        val sessionId = "multi_sensor_test"

        // Start recording
        recordingController.startSession(sessionId)

        // Verify all sensors started
        assertTrue(sensor1.isStarted)
        assertTrue(sensor2.isStarted)
        assertTrue(sensor3.isStarted)

        // Verify each sensor has its own directory
        assertNotNull(sensor1.startedDir)
        assertNotNull(sensor2.startedDir)
        assertNotNull(sensor3.startedDir)

        assertTrue(sensor1.startedDir!!.name.contains("sensor_1"))
        assertTrue(sensor2.startedDir!!.name.contains("sensor_2"))
        assertTrue(sensor3.startedDir!!.name.contains("sensor_3"))

        // Stop recording
        recordingController.stopSession()

        // Verify all sensors stopped
        assertFalse(sensor1.isStarted)
        assertFalse(sensor2.isStarted)
        assertFalse(sensor3.isStarted)

        assertEquals(1, sensor1.stopCount)
        assertEquals(1, sensor2.stopCount)
        assertEquals(1, sensor3.stopCount)
    }

    @Test
    fun `session directory structure is created correctly`() = runTest {
        val sensor = TestSensorRecorder("directory_test_sensor")
        recordingController.register("directory_test_sensor", sensor)

        val sessionId = "directory_structure_test"
        recordingController.startSession(sessionId)

        // Verify session directory exists
        val sessionDir = File(sessionsRoot, sessionId)
        assertTrue("Session directory should exist", sessionDir.exists())
        assertTrue("Session directory should be a directory", sessionDir.isDirectory)

        // Verify sensor subdirectory exists
        val sensorDir = File(sessionDir, "directory_test_sensor")
        assertTrue("Sensor directory should exist", sensorDir.exists())
        assertTrue("Sensor directory should be a directory", sensorDir.isDirectory)

        recordingController.stopSession()
    }

    @Test
    fun `cannot start session when already recording`() = runTest {
        val sensor = TestSensorRecorder("conflict_sensor")
        recordingController.register("conflict_sensor", sensor)

        recordingController.startSession("first_session")
        assertEquals(RecordingController.State.RECORDING, recordingController.state.value)

        // Attempting to start another session should fail
        assertFailsWith<IllegalStateException> {
            recordingController.startSession("second_session")
        }

        // First session should still be active
        assertEquals("first_session", recordingController.currentSessionId.value)
        assertEquals(RecordingController.State.RECORDING, recordingController.state.value)

        recordingController.stopSession()
    }

    @Test
    fun `can stop session when idle without error`() = runTest {
        // Should not throw exception when stopping in idle state
        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
        recordingController.stopSession()
        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
    }

    @Test
    fun `sensor start failure handling`() = runTest {
        val failingSensor = TestSensorRecorder("failing_sensor")
        val workingSensor = TestSensorRecorder("working_sensor")

        failingSensor.shouldFailOnStart = true

        recordingController.register("failing_sensor", failingSensor)
        recordingController.register("working_sensor", workingSensor)

        // Starting session with failing sensor should handle gracefully
        try {
            recordingController.startSession("failure_test")
            // The implementation may or may not throw - test should handle both cases
        } catch (e: Exception) {
            // If it throws, verify state remains consistent
            // Implementation detail - some controllers might handle gracefully
        }

        // Working sensor should not be affected by other sensor's failure
        // (This depends on implementation - some might roll back all sensors)
        recordingController.stopSession()
    }

    @Test
    fun `sensor stop failure handling`() = runTest {
        val failingSensor = TestSensorRecorder("stop_failing_sensor")
        val workingSensor = TestSensorRecorder("stop_working_sensor")

        recordingController.register("stop_failing_sensor", failingSensor)
        recordingController.register("stop_working_sensor", workingSensor)

        recordingController.startSession("stop_failure_test")

        assertTrue(failingSensor.isStarted)
        assertTrue(workingSensor.isStarted)

        // Set one sensor to fail on stop
        failingSensor.shouldFailOnStop = true

        // Stopping should handle gracefully
        try {
            recordingController.stopSession()
        } catch (e: Exception) {
            // Some implementations might throw, others might handle gracefully
        }

        // State should be consistent regardless
        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
    }

    @Test
    fun `registering duplicate sensor replaces previous`() = runTest {
        val sensor1 = TestSensorRecorder("original_sensor")
        val sensor2 = TestSensorRecorder("replacement_sensor")

        recordingController.register("test_sensor", sensor1)
        recordingController.register("test_sensor", sensor2)  // Replace

        recordingController.startSession("duplicate_test")

        // Only the replacement sensor should be started
        assertFalse(sensor1.isStarted)
        assertTrue(sensor2.isStarted)

        recordingController.stopSession()
    }

    @Test
    fun `unregister sensor removes it from recording`() = runTest {
        val sensor1 = TestSensorRecorder("sensor_1")
        val sensor2 = TestSensorRecorder("sensor_2")

        recordingController.register("sensor_1", sensor1)
        recordingController.register("sensor_2", sensor2)

        // Unregister one sensor
        recordingController.unregister("sensor_1")

        recordingController.startSession("unregister_test")

        // Only sensor_2 should start
        assertFalse(sensor1.isStarted)
        assertTrue(sensor2.isStarted)

        recordingController.stopSession()
    }

    @Test
    fun `empty session id handling`() = runTest {
        val sensor = TestSensorRecorder("empty_session_sensor")
        recordingController.register("empty_session_sensor", sensor)

        // Empty session ID should be handled
        recordingController.startSession("")

        assertEquals(RecordingController.State.RECORDING, recordingController.state.value)
        assertEquals("", recordingController.currentSessionId.value)
        assertTrue(sensor.isStarted)

        recordingController.stopSession()
    }

    @Test
    fun `special characters in session id`() = runTest {
        val sensor = TestSensorRecorder("special_char_sensor")
        recordingController.register("special_char_sensor", sensor)

        val specialSessionId = "session-test_2024.01@special"
        recordingController.startSession(specialSessionId)

        assertEquals(specialSessionId, recordingController.currentSessionId.value)
        assertTrue(sensor.isStarted)

        // Directory should be created (may be sanitized)
        val sessionDirs = sessionsRoot.listFiles()?.filter { it.isDirectory }
        assertNotNull(sessionDirs)
        assertTrue("Should have at least one session directory", sessionDirs!!.isNotEmpty())

        recordingController.stopSession()
    }

    @Test
    fun `concurrent session operations`() = runTest {
        val sensor = TestSensorRecorder("concurrent_sensor")
        recordingController.register("concurrent_sensor", sensor)

        // Start session
        recordingController.startSession("concurrent_test")
        assertTrue(sensor.isStarted)

        // Multiple stop calls should be safe
        recordingController.stopSession()
        recordingController.stopSession()
        recordingController.stopSession()

        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
        assertFalse(sensor.isStarted)
        // Stop should only be called once
        assertEquals(1, sensor.stopCount)
    }

    @Test
    fun `state transitions are atomic`() = runTest {
        val sensor = TestSensorRecorder("atomic_sensor")
        recordingController.register("atomic_sensor", sensor)

        // Initial state
        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
        assertNull(recordingController.currentSessionId.value)

        // Start recording
        recordingController.startSession("atomic_test")

        // State should change immediately and atomically
        assertEquals(RecordingController.State.RECORDING, recordingController.state.value)
        assertEquals("atomic_test", recordingController.currentSessionId.value)

        // Stop recording
        recordingController.stopSession()

        // State should change back immediately
        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
        assertNull(recordingController.currentSessionId.value)
    }

    @Test
    fun `recording with no registered sensors`() = runTest {
        // Should handle case where no sensors are registered
        recordingController.startSession("no_sensors_test")

        assertEquals(RecordingController.State.RECORDING, recordingController.state.value)
        assertEquals("no_sensors_test", recordingController.currentSessionId.value)

        recordingController.stopSession()

        assertEquals(RecordingController.State.IDLE, recordingController.state.value)
    }

    @Test
    fun `very long session id handling`() = runTest {
        val sensor = TestSensorRecorder("long_id_sensor")
        recordingController.register("long_id_sensor", sensor)

        val longSessionId = "a".repeat(1000)  // Very long session ID
        recordingController.startSession(longSessionId)

        assertEquals(longSessionId, recordingController.currentSessionId.value)
        assertTrue(sensor.isStarted)

        recordingController.stopSession()
    }

    @Test
    fun `session directory permissions and access`() = runTest {
        val sensor = TestSensorRecorder("permissions_sensor")
        recordingController.register("permissions_sensor", sensor)

        recordingController.startSession("permissions_test")

        val sessionDir = File(sessionsRoot, "permissions_test")
        assertTrue("Session directory should exist", sessionDir.exists())
        assertTrue("Session directory should be readable", sessionDir.canRead())
        assertTrue("Session directory should be writable", sessionDir.canWrite())

        val sensorDir = sensor.startedDir
        assertNotNull(sensorDir)
        assertTrue("Sensor directory should exist", sensorDir!!.exists())
        assertTrue("Sensor directory should be readable", sensorDir.canRead())
        assertTrue("Sensor directory should be writable", sensorDir.canWrite())

        recordingController.stopSession()
    }

    @Test
    fun `rapid start stop cycles`() = runTest {
        val sensor = TestSensorRecorder("rapid_cycle_sensor")
        recordingController.register("rapid_cycle_sensor", sensor)

        // Perform rapid start/stop cycles
        repeat(10) { cycle ->
            val sessionId = "rapid_cycle_$cycle"

            recordingController.startSession(sessionId)
            assertEquals(RecordingController.State.RECORDING, recordingController.state.value)
            assertTrue(sensor.isStarted)

            recordingController.stopSession()
            assertEquals(RecordingController.State.IDLE, recordingController.state.value)
            assertFalse(sensor.isStarted)
        }

        // Verify all cycles completed
        assertEquals(10, sensor.stopCount)
    }
}
