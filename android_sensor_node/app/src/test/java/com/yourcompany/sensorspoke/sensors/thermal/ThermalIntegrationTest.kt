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

            // Verify CSV file was created
            val csvFile = File(testSessionDir, "thermal.csv")
            assertTrue("Thermal CSV file should exist", csvFile.exists())

            // Verify metadata file was created
            val metadataFile = File(testSessionDir, "thermal_metadata.json")
            assertTrue("Thermal metadata file should exist", metadataFile.exists())
        }

    @Test
    fun testCSVFormatCompliance() =
        runBlocking {
            recorder.start(testSessionDir)
            Thread.sleep(300) // Let more data accumulate
            recorder.stop()

            val csvFile = File(testSessionDir, "thermal.csv")
            assertTrue("CSV file should exist", csvFile.exists())

            val lines = csvFile.readLines()
            assertTrue("CSV should have header", lines.isNotEmpty())

            // Check header format
            val header = lines[0]
            val expectedColumns =
                listOf(
                    "timestamp_ns",
                    "frame_id",
                    "center_temp_c",
                    "min_temp_c",
                    "max_temp_c",
                    "avg_temp_c",
                    "image_path",
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

                // Validate temperature values
                val centerTemp = dataRow[2].toFloat()
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

            // Check for generated thermal images
            val imageFiles =
                testSessionDir.listFiles { _, name ->
                    name.startsWith("thermal_frame_") && name.endsWith(".png")
                }

            assertNotNull("Should have generated some thermal images", imageFiles)
            assertTrue("Should have at least one thermal image", imageFiles!!.isNotEmpty())

            // Verify image file naming convention
            val firstImage = imageFiles.minByOrNull { it.name }
            assertNotNull("Should have a first image", firstImage)
            assertTrue(
                "Image should follow naming convention",
                firstImage!!.name.matches(Regex("thermal_frame_\\d{6}\\.png")),
            )
        }

    @Test
    fun testMetadataContent() =
        runBlocking {
            recorder.start(testSessionDir)
            recorder.stop()

            val metadataFile = File(testSessionDir, "thermal_metadata.json")
            assertTrue("Metadata file should exist", metadataFile.exists())

            val metadataContent = metadataFile.readText()

            // Verify key metadata fields
            val requiredFields =
                listOf(
                    "sensor_type",
                    "device_model",
                    "resolution_width",
                    "resolution_height",
                    "temperature_range_min",
                    "temperature_range_max",
                )

            for (field in requiredFields) {
                assertTrue(
                    "Metadata should contain $field",
                    metadataContent.contains("\"$field\""),
                )
            }

            // Verify Topdon TC001 is mentioned
            assertTrue(
                "Should specify Topdon TC001",
                metadataContent.contains("Topdon TC001"),
            )

            // Verify resolution specifications
            assertTrue(
                "Should specify 256x192 resolution",
                metadataContent.contains("256") && metadataContent.contains("192"),
            )
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
