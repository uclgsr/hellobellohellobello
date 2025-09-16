package com.yourcompany.sensorspoke.sensors.rgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import com.yourcompany.sensorspoke.utils.PreviewBus
import com.yourcompany.sensorspoke.utils.TimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RGB camera recorder using CameraX for video recording and image capture.
 * Implements the MVP functionality for the hellobellohellobello system.
 */
class RgbCameraRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) : SensorRecorder {
    companion object {
        private const val TAG = "RgbCameraRecorder"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var csvWriter: java.io.BufferedWriter? = null
    private var csvFile: File? = null
    private var frameCount = 0
    private var videoStartTime: Long = 0L

    override suspend fun start(sessionDir: File) {
        Log.i(TAG, "Starting RGB camera recording in session: ${sessionDir.absolutePath}")

        // Ensure directories
        val framesDir = File(sessionDir, "frames").apply { mkdirs() }

        // Open CSV for frame metadata - enhanced with video correlation
        csvFile = File(sessionDir, "rgb_frames.csv")
        csvWriter = java.io.BufferedWriter(java.io.FileWriter(csvFile!!, true))
        if (csvFile!!.length() == 0L) {
            csvWriter!!.write("timestamp_ns,timestamp_ms,frame_number,filename,file_size_bytes,video_relative_time_ms,video_frame_estimate\n")
            csvWriter!!.flush()
        }

        // Initialize CameraX
        initializeCameraX()

        // Start video recording
        startVideoRecording(File(sessionDir, "video.mp4"))

        // Start frame capture process
        startFrameCapture(framesDir)
    }

    override suspend fun stop() {
        Log.i(TAG, "Stopping RGB camera recording")

        // Stop recording
        recording?.stop()
        recording = null

        // Cancel capture job and wait for completion
        captureJob?.let {
            it.cancel()
            it.join() // Wait for completion to avoid race condition
        }
        captureJob = null

        // Unbind camera
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        imageCapture = null
        videoCapture = null
        videoStartTime = 0L

        // Close CSV resources
        csvWriter?.flush()
        csvWriter?.close()
        csvWriter = null
        csvFile = null

        // Shutdown executor
        executor.shutdown()

        Log.i(TAG, "RGB camera recording stopped")
    }

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

        Log.i(TAG, "CameraX initialized successfully with enhanced controls")
    }

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

    private fun startVideoRecording(videoFile: File) {
        val sessionStartTs = TimeManager.nowNanos()
        videoStartTime = sessionStartTs
        val videoFileWithTimestamp = File(videoFile.parent, "video_$sessionStartTs.mp4")

        val outputOpts = FileOutputOptions.Builder(videoFileWithTimestamp).build()
        recording = videoCapture!!.output
            .prepareRecording(context, outputOpts)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                // Handle recording events for better synchronization logging
                Log.d(TAG, "Recording event: $event")
                when (event) {
                    is androidx.camera.video.VideoRecordEvent.Start -> {
                        Log.i(TAG, "Video recording actually started at: ${System.nanoTime()}")
                    }
                    is androidx.camera.video.VideoRecordEvent.Finalize -> {
                        Log.i(TAG, "Video recording finalized: ${event.outputResults}")
                    }
                }
            }

        Log.i(TAG, "Video recording started: ${videoFileWithTimestamp.absolutePath} at timestamp $sessionStartTs")
    }

    private fun startFrameCapture(framesDir: File) {
        captureJob = scope.launch {
            Log.i(TAG, "Starting frame capture loop")

            while (isActive) {
                try {
                    captureFrame(framesDir)
                    delay(33) // ~30 FPS for high-quality data capture (was ~6-7 FPS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error capturing frame: ${e.message}", e)
                    delay(1000) // Wait longer on error
                }
            }
        }
    }

    private suspend fun captureFrame(framesDir: File) {
        val timestampNs = TimeManager.nowNanos()
        val timestampMs = System.currentTimeMillis()
        frameCount++

        val filename = "frame_$timestampNs.jpg"
        val outputFile = File(framesDir, filename)
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture?.takePicture(
            outputFileOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Frame capture error: ${exception.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val fileSize = if (outputFile.exists()) outputFile.length() else 0

                        // Calculate video correlation metrics
                        val videoRelativeTimeMs = if (videoStartTime > 0) {
                            ((timestampNs - videoStartTime) / 1_000_000).toInt()
                        } else {
                            0
                        }

                        // Estimate video frame number assuming 30 FPS
                        val estimatedVideoFrame = if (videoRelativeTimeMs > 0) {
                            (videoRelativeTimeMs * 30 / 1000).toInt()
                        } else {
                            0
                        }

                        // Enhanced CSV logging with video correlation
                        csvWriter?.apply {
                            write("$timestampNs,$timestampMs,$frameCount,$filename,$fileSize,$videoRelativeTimeMs,$estimatedVideoFrame\n")
                            flush()
                        }

                        // Generate preview for UI
                        generatePreview(outputFile, timestampNs)

                        Log.d(TAG, "Captured frame: $filename ($fileSize bytes) - Video time: ${videoRelativeTimeMs}ms, Est. frame: $estimatedVideoFrame")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing captured frame: ${e.message}", e)
                    }
                }
            },
        )
    }

    private fun generatePreview(imageFile: File, timestamp: Long) {
        try {
            if (!imageFile.exists()) return

            // Load and downsample the image for preview
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4 // 1/4 size for preview
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
            if (bitmap != null) {
                // Scale to standard preview size
                val previewBitmap = Bitmap.createScaledBitmap(bitmap, 320, 240, true)

                // Compress to JPEG for preview bus
                val baos = ByteArrayOutputStream()
                previewBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val previewBytes = baos.toByteArray()

                // Emit preview
                PreviewBus.emit(previewBytes, timestamp)

                // Cleanup
                baos.close()
                if (previewBitmap != bitmap) {
                    bitmap.recycle()
                }
                previewBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating preview: ${e.message}", e)
        }
    }
}
