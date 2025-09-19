package com.yourcompany.sensorspoke.sensors.rgb

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for Camera2RawDngManager
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class Camera2RawDngManagerTest {

    private lateinit var context: Context
    private lateinit var camera2RawDngManager: Camera2RawDngManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        camera2RawDngManager = Camera2RawDngManager(context)
    }

    @Test
    fun `test Samsung device detection`() {
        // Setup Samsung device
        val shadowBuild = Shadow.extract<ShadowBuild>(Build::class.java)
        shadowBuild.setManufacturer("Samsung")
        shadowBuild.setModel("SM-S901B")

        assertTrue(camera2RawDngManager.getCamera2Status().isSamsungDevice)
        assertTrue(camera2RawDngManager.isSamsungLevel3Device())
    }

    @Test
    fun `test non-Samsung device detection`() {
        // Setup non-Samsung device
        val shadowBuild = Shadow.extract<ShadowBuild>(Build::class.java)
        shadowBuild.setManufacturer("Google")
        shadowBuild.setModel("Pixel 7")

        val status = camera2RawDngManager.getCamera2Status()
        assertFalse(status.isSamsungDevice)
        assertFalse(camera2RawDngManager.isSamsungLevel3Device())
    }

    @Test
    fun `test initialization state`() = runTest {
        assertEquals(
            Camera2RawDngManager.Camera2State.UNINITIALIZED,
            camera2RawDngManager.cameraState.value
        )
        
        assertFalse(camera2RawDngManager.supportsRawDng())
    }

    @Test
    fun `test camera2 status data class`() {
        val status = camera2RawDngManager.getCamera2Status()
        
        assertNotNull(status)
        assertEquals(Camera2RawDngManager.Camera2State.UNINITIALIZED, status.state)
        assertNotNull(status.deviceModel)
    }

    @Test
    fun `test Samsung Level 3 device models`() {
        val testModels = listOf(
            "SM-S901B", // Galaxy S22
            "SM-S906U", // Galaxy S22+
            "SM-S908W", // Galaxy S22 Ultra
            "SM-S911B", // Galaxy S23
            "SM-S921U"  // Galaxy S24
        )

        testModels.forEach { model ->
            val shadowBuild = Shadow.extract<ShadowBuild>(Build::class.java)
            shadowBuild.setManufacturer("Samsung")
            shadowBuild.setModel(model)

            val manager = Camera2RawDngManager(context)
            assertTrue(manager.isSamsungLevel3Device(), "Model $model should be detected as Samsung Level 3")
        }
    }

    @Test
    fun `test non-Samsung Level 3 device models`() {
        val testModels = listOf(
            "SM-A515F", // Galaxy A51 (not Level 3)
            "SM-M317F", // Galaxy M31 (not Level 3)
            "Pixel 6",  // Google device
            "iPhone 14" // Apple device
        )

        testModels.forEach { model ->
            val shadowBuild = Shadow.extract<ShadowBuild>(Build::class.java)
            shadowBuild.setManufacturer("Samsung")
            shadowBuild.setModel(model)

            val manager = Camera2RawDngManager(context)
            assertFalse(manager.isSamsungLevel3Device(), "Model $model should NOT be detected as Samsung Level 3")
        }
    }

    @Test
    fun `test capture RAW DNG without initialization`() = runTest {
        val outputFile = File.createTempFile("test_raw", ".dng")
        
        try {
            val result = camera2RawDngManager.captureRawDng(outputFile)
            assertFalse(result, "RAW DNG capture should fail when not initialized")
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `test cleanup`() {
        camera2RawDngManager.cleanup()
        
        assertEquals(
            Camera2RawDngManager.Camera2State.UNINITIALIZED,
            camera2RawDngManager.cameraState.value
        )
    }

    @Test
    fun `test device info data class`() {
        val deviceInfo = Camera2RawDngManager.Camera2DeviceInfo(
            deviceModel = "SM-S901B",
            isSamsungDevice = true,
            supportsCamera2Level3 = true,
            supportsRawDng = true,
            backCameraId = "0",
            frontCameraId = "1",
            maxRawSize = android.util.Size(4032, 3024),
            availableRawFormats = listOf(android.graphics.ImageFormat.RAW_SENSOR),
            hardwareLevel = android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            capabilities = setOf(
                android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            )
        )

        assertEquals("SM-S901B", deviceInfo.deviceModel)
        assertTrue(deviceInfo.isSamsungDevice)
        assertTrue(deviceInfo.supportsCamera2Level3)
        assertTrue(deviceInfo.supportsRawDng)
        assertEquals("0", deviceInfo.backCameraId)
        assertEquals("1", deviceInfo.frontCameraId)
        assertNotNull(deviceInfo.maxRawSize)
        assertTrue(deviceInfo.availableRawFormats.isNotEmpty())
        assertTrue(deviceInfo.capabilities.isNotEmpty())
    }
}