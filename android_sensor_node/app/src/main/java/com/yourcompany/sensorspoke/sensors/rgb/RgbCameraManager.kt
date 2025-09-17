package com.yourcompany.sensorspoke.sensors.rgb

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RgbCameraManager handles RGB camera lifecycle management and configuration.
 * Separates camera management concerns from data recording logic.
 *
 * This class is responsible for:
 * - Camera initialization and configuration
 * - Camera state monitoring
 * - Camera controls (focus, exposure)
 * - Error handling and recovery
 */
class RgbCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) {
    companion object {
        private const val TAG = "RgbCameraManager"
    }

    // Camera state management
    private val _cameraState = MutableStateFlow(CameraState.UNINITIALIZED)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _cameraInfo = MutableStateFlow<CameraInfo?>(null)
    val cameraInfo: StateFlow<CameraInfo?> = _cameraInfo.asStateFlow()

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    /**
     * Camera states for RGB camera management
     */
    enum class CameraState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        RECORDING,
        ERROR,
    }

    /**
     * Camera information container
     */
    data class CameraInfo(
        val cameraId: String,
        val supportedQualities: List<Quality>,
        val hasFlash: Boolean = false,
        val isBackCamera: Boolean = true,
        val maxZoomRatio: Float = 1.0f,
    )

    /**
     * Initialize the camera system
     */
    suspend fun initialize(): Boolean {
        return try {
            Log.i(TAG, "Initializing RGB camera manager")
            _cameraState.value = CameraState.INITIALIZING

            initializeCameraX()

            _cameraState.value = CameraState.READY
            Log.i(TAG, "RGB camera manager initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RGB camera manager: ${e.message}", e)
            _cameraState.value = CameraState.ERROR
            false
        }
    }

    /**
     * Initialize CameraX components
     */
    private suspend fun initializeCameraX() {
        val provider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider = provider

        // Build recorder for 4K60fps video - high performance mode
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD), // 4K priority, fallback to lower
                ),
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Build image capture for high-quality frames with enhanced settings
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(0) // No rotation for maximum quality
            .setFlashMode(ImageCapture.FLASH_MODE_OFF) // Avoid artifacts in research data
            .build()

        // Create preview for focus metering (not displayed, just for camera controls)
        val preview = Preview.Builder().build()

        // Bind use cases to lifecycle
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            videoCapture,
            imageCapture,
            preview,
        )

        // Configure enhanced camera controls for scientific recording
        configureCameraControls()

        // Update camera info
        updateCameraInfo()

        Log.i(TAG, "CameraX initialized successfully with enhanced controls")
    }

    /**
     * Configure camera controls for optimal recording
     */
    private fun configureCameraControls() {
        try {
            camera?.let { cam ->
                val cameraControl = cam.cameraControl
                val cameraInfo = cam.cameraInfo

                // Simple autofocus to center of frame - using basic CameraX API
                try {
                    // Create a simple focus point at center of frame
                    val factory = SurfaceOrientedMeteringPointFactory(1.0f, 1.0f)
                    val centerPoint = factory.createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(centerPoint).build()

                    cameraControl.startFocusAndMetering(action)
                    Log.d(TAG, "Focus metering started at center point")
                } catch (focusError: Exception) {
                    Log.w(TAG, "Could not configure focus metering: ${focusError.message}")
                }

                // Set neutral exposure compensation for consistent research conditions
                try {
                    val exposureRange = cameraInfo.exposureState.exposureCompensationRange
                    if (exposureRange.contains(0)) {
                        cameraControl.setExposureCompensationIndex(0) // Neutral exposure
                        Log.d(TAG, "Exposure compensation set to neutral")
                    }
                } catch (exposureError: Exception) {
                    Log.w(TAG, "Could not configure exposure: ${exposureError.message}")
                }

                Log.i(TAG, "Camera controls configured successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure enhanced camera controls: ${e.message}", e)
            // Continue without enhanced controls - basic functionality will still work
        }
    }

    /**
     * Update camera information
     */
    private fun updateCameraInfo() {
        camera?.let { cam ->
            val info = cam.cameraInfo
            val cameraId = when (cameraSelector) {
                CameraSelector.DEFAULT_BACK_CAMERA -> "BACK"
                CameraSelector.DEFAULT_FRONT_CAMERA -> "FRONT"
                else -> "UNKNOWN"
            }

            _cameraInfo.value = CameraInfo(
                cameraId = cameraId,
                supportedQualities = listOf(Quality.UHD, Quality.FHD, Quality.HD),
                hasFlash = info.hasFlashUnit(),
                isBackCamera = cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA,
                maxZoomRatio = info.zoomState.value?.maxZoomRatio ?: 1.0f,
            )
        }
    }

    /**
     * Get image capture use case
     */
    fun getImageCapture(): ImageCapture? = imageCapture

    /**
     * Get video capture use case
     */
    fun getVideoCapture(): VideoCapture<Recorder>? = videoCapture

    /**
     * Check if camera is ready for recording
     */
    fun isReady(): Boolean = _cameraState.value == CameraState.READY

    /**
     * Update recording state
     */
    fun updateRecordingState(isRecording: Boolean) {
        _cameraState.value = if (isRecording) CameraState.RECORDING else CameraState.READY
    }

    /**
     * Clean up camera resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up RGB camera manager")

        // Unbind camera
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        imageCapture = null
        videoCapture = null

        _cameraState.value = CameraState.UNINITIALIZED
        _cameraInfo.value = null
    }
}
