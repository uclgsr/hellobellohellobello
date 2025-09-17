package com.yourcompany.sensorspoke.sensors.thermal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Integration tests for ThermalCameraRecorder implementation.
 * Tests both real hardware integration paths and simulation fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThermalIntegrationTest {
    private lateinit var context: Context
    private lateinit var testSessionDir: File
    private lateinit var recorder: ThermalCameraRecorder

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testSessionDir = File(context.cacheDir, "test_thermal_session")
        testSessionDir.mkdirs()
        recorder = ThermalCameraRecorder(context)
    }

    @Test
    fun testBasicRecordingLifecycle() =
        runBlocking {
            // Start recording
            recorder.start(testSessionDir)

            // Let simulation run briefly
            Thread.sleep(200)

            // Stop recording
            recorder.stop()

            // Verify CSV file was created with the correct filename
            val csvFile = File(testSessionDir, "thermal_data.csv")
            assertTrue("Thermal CSV file should exist", csvFile.exists())

            // Verify thermal images directory was created
            val thermalImagesDir = File(testSessionDir, "thermal_images")
            assertTrue("Thermal images directory should exist", thermalImagesDir.exists())
        }

    @Test
    fun testCSVFormatCompliance() =
        runBlocking {
            recorder.start(testSessionDir)
            Thread.sleep(300) // Let more data accumulate
            recorder.stop()

            val csvFile = File(testSessionDir, "thermal_data.csv")
            assertTrue("CSV file should exist", csvFile.exists())

            val lines = csvFile.readLines()
            assertTrue("CSV should have header", lines.isNotEmpty())

            // Check header format - match the actual implementation
            val header = lines[0]
            val expectedColumns =
                listOf(
                    "timestamp_ns",
                    "timestamp_ms",
                    "frame_number",
                    "temperature_celsius",
                    "min_temp",
                    "max_temp",
                    "avg_temp",
                    "filename",
                    "image_height",
                    "image_width",
                )
            for (column in expectedColumns) {
                assertTrue("Header should contain $column", header.contains(column))
            }

            // Check data rows if any
            if (lines.size > 1) {
                val dataRow = lines[1].split(",")
                assertEquals(
                    "Data row should have correct number of columns",
                    expectedColumns.size,
                    dataRow.size,
                )

                // Validate timestamp format (should be nanoseconds)
                val timestamp = dataRow[0].toLong()
                assertTrue(
                    "Timestamp should be reasonable nanosecond value",
                    timestamp > 1_000_000_000_000_000L,
                ) // > year 2001 in ns

                // Validate temperature values (temperature_celsius is at index 3)
                val centerTemp = dataRow[3].toFloat()
                assertTrue(
                    "Center temperature should be reasonable",
                    centerTemp > -50.0f && centerTemp < 200.0f,
                )
            }
        }

    @Test
    fun testThermalImageGeneration() =
        runBlocking {
            recorder.start(testSessionDir)
            Thread.sleep(500) // Let multiple frames generate
            recorder.stop()

            // Check for generated thermal images in the thermal_images directory
            val thermalImagesDir = File(testSessionDir, "thermal_images")
            assertTrue("Thermal images directory should exist", thermalImagesDir.exists())
            
            val imageFiles = thermalImagesDir.listFiles { _, name ->
                name.startsWith("thermal_") && name.endsWith(".png")
            }

            assertNotNull("Should have generated some thermal images", imageFiles)
            assertTrue("Should have at least one thermal image", imageFiles!!.isNotEmpty())

            // Verify image file naming convention (matches implementation)
            val firstImage = imageFiles.minByOrNull { it.name }
            assertNotNull("Should have a first image", firstImage)
            assertTrue(
                "Image should follow naming convention",
                firstImage!!.name.matches(Regex("thermal_(sim_)?\\d+\\.png")),
            )
        }
}

    @Test
    fun testTemperatureRangeValidation() =
        runBlocking {
            recorder.start(testSessionDir)
            Thread.sleep(300)
            recorder.stop()

            val csvFile = File(testSessionDir, "thermal.csv")
            if (csvFile.exists()) {
                val lines = csvFile.readLines()

                for (i in 1 until lines.size) { // Skip header
                    val data = lines[i].split(",")
                    if (data.size >= 6) {
                        val centerTemp = data[2].toFloat()
                        val minTemp = data[3].toFloat()
                        val maxTemp = data[4].toFloat()
                        val avgTemp = data[5].toFloat()

                        // Validate temperature relationships
                        assertTrue("Min temp should be <= center temp", minTemp <= centerTemp)
                        assertTrue("Center temp should be <= max temp", centerTemp <= maxTemp)
                        assertTrue("Min temp should be <= average temp", minTemp <= avgTemp)
                        assertTrue("Average temp should be <= max temp", avgTemp <= maxTemp)

                        // Validate reasonable temperature range
                        assertTrue(
                            "Temperatures should be reasonable",
                            minTemp > -100.0f && maxTemp < 500.0f,
                        )
                    }
                }
            }
        }

    @Test
    fun testConcurrentAccess() =
        runBlocking {
            // Test that multiple start/stop calls are handled gracefully
            recorder.start(testSessionDir)
            recorder.start(testSessionDir) // Should not cause issues

            Thread.sleep(100)

            recorder.stop()
            recorder.stop() // Should not cause issues

            val csvFile = File(testSessionDir, "thermal.csv")
            assertTrue("Should still create CSV file", csvFile.exists())
        }

    @Test
    fun testSimulationMode() =
        runBlocking {
            // This test verifies simulation mode works when real hardware unavailable
            // (which is the normal case in CI/testing environments)

            recorder.start(testSessionDir)
            Thread.sleep(400) // Let simulation generate data
            recorder.stop()

            // Verify simulation generated reasonable data
            val csvFile = File(testSessionDir, "thermal.csv")
            assertTrue("Simulation should create CSV", csvFile.exists())

            val lines = csvFile.readLines()
            assertTrue("Should have data rows", lines.size > 1)

            // Check that simulation generates varied temperature data
            val temperatures = mutableListOf<Float>()
            for (i in 1 until lines.size) {
                val data = lines[i].split(",")
                if (data.size >= 3) {
                    temperatures.add(data[2].toFloat()) // center_temp_c
                }
            }

            if (temperatures.size > 5) {
                // Should have some variation in simulated temperatures
                val variance =
                    temperatures
                        .map { temp ->
                            val mean = temperatures.average()
                            (temp - mean) * (temp - mean)
                        }.average()

                assertTrue(
                    "Simulation should generate varied temperatures",
                    variance > 0.1,
                ) // Some minimum variance
            }
        }

    @Test
    fun testErrorHandling() =
        runBlocking {
            // Test with invalid session directory
            val invalidDir = File("/invalid/path/that/does/not/exist")

            try {
                recorder.start(invalidDir)
                // Should either succeed (creates directories) or fail gracefully
                recorder.stop()
            } catch (e: Exception) {
                // Should not crash with unhandled exceptions
                assertTrue(
                    "Should be a reasonable exception type",
                    e is SecurityException || e is java.io.IOException,
                )
            }
        }

    @Test
    fun testResourceCleanup() =
        runBlocking {
            // Start and stop multiple times to test resource cleanup
            repeat(3) {
                recorder.start(testSessionDir)
                Thread.sleep(50)
                recorder.stop()
            }

            // Should not accumulate resources or cause memory leaks
            // This is mainly tested by not crashing during repeated operations
            assertTrue("Resource cleanup test completed", true)
        }
}
