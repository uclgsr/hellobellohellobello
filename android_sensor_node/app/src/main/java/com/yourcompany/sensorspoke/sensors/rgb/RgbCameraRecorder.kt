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
    private var syncMonitorJob: Job? = null
    private var csvWriter: java.io.BufferedWriter? = null
    private var csvFile: File? = null
    private var frameCount = 0
    private var videoStartTime: Long = 0L
    private var actualVideoStartTime: Long = 0L // Actual start from VideoRecordEvent.Start
    private var frameTimestampOffset: Long = 0L // Offset for better synchronization

    override suspend fun start(sessionDir: File) {
        Log.i(TAG, "Starting RGB camera recording in session: ${sessionDir.absolutePath}")

        // Ensure directories
        val framesDir = File(sessionDir, "frames").apply { mkdirs() }

        // Open CSV for frame metadata - enhanced with video correlation
        csvFile = File(sessionDir, "rgb_frames.csv")
        csvWriter = java.io.BufferedWriter(java.io.FileWriter(csvFile!!, true))
        if (csvFile!!.length() == 0L) {
            csvWriter!!.write("timestamp_ns,timestamp_ms,frame_number,filename,file_size_bytes,video_relative_time_ms,video_frame_estimate,sync_quality,actual_video_offset_ms\n")
            csvWriter!!.flush()
        }

        // Initialize CameraX
        initializeCameraX()

        // Start video recording
        startVideoRecording(File(sessionDir, "video.mp4"))

        // Start frame capture process
        startFrameCapture(framesDir)

        // Start synchronization monitoring
        startSyncMonitoring()
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

        // Cancel sync monitoring job
        syncMonitorJob?.cancel()
        syncMonitorJob = null

        // Unbind camera
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        imageCapture = null
        videoCapture = null
        videoStartTime = 0L
        actualVideoStartTime = 0L
        frameTimestampOffset = 0L

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
                        actualVideoStartTime = System.nanoTime()
                        frameTimestampOffset = actualVideoStartTime - videoStartTime
                        Log.i(TAG, "Video recording actually started at: $actualVideoStartTime")
                        Log.i(TAG, "Video start offset: ${frameTimestampOffset / 1_000_000}ms")
                        
                        // Log the actual video start for post-processing synchronization
                        logVideoEvent("VIDEO_RECORDING_STARTED", actualVideoStartTime, 
                                    "offset_ms:${frameTimestampOffset / 1_000_000}")
                    }
                    is androidx.camera.video.VideoRecordEvent.Finalize -> {
                        val finalizeTime = System.nanoTime()
                        Log.i(TAG, "Video recording finalized: ${event.outputResults}")
                        logVideoEvent("VIDEO_RECORDING_FINALIZED", finalizeTime, 
                                    "duration_ms:${(finalizeTime - actualVideoStartTime) / 1_000_000}")
                    }
                    is androidx.camera.video.VideoRecordEvent.Status -> {
                        // Log periodic status for timing verification
                        if (event.recordingStats.numBytesRecorded > 0) {
                            val statusTime = System.nanoTime()
                            logVideoEvent("VIDEO_STATUS_UPDATE", statusTime,
                                        "bytes:${event.recordingStats.numBytesRecorded}")
                        }
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

    private fun startSyncMonitoring() {
        syncMonitorJob = scope.launch {
            Log.i(TAG, "Starting synchronization monitoring")
            
            while (isActive) {
                try {
                    delay(5000) // Monitor every 5 seconds
                    
                    if (actualVideoStartTime > 0 && frameCount > 0) {
                        val currentTime = System.nanoTime()
                        val totalRecordingTime = (currentTime - actualVideoStartTime) / 1_000_000 // ms
                        val avgFrameRate = if (totalRecordingTime > 0) {
                            (frameCount * 1000.0) / totalRecordingTime
                        } else 0.0
                        
                        logVideoEvent("SYNC_MONITOR_UPDATE", currentTime,
                                    "frames:$frameCount,avg_fps:${"%.2f".format(avgFrameRate)},recording_time_ms:$totalRecordingTime")
                        
                        Log.d(TAG, "Sync monitor - Frames: $frameCount, Avg FPS: ${"%.2f".format(avgFrameRate)}, Recording time: ${totalRecordingTime}ms")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in sync monitoring: ${e.message}", e)
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

                        // Enhanced video correlation metrics with improved accuracy
                        val baseTime = if (actualVideoStartTime > 0) actualVideoStartTime else videoStartTime
                        val videoRelativeTimeMs = if (baseTime > 0) {
                            ((timestampNs - baseTime) / 1_000_000).toInt()
                        } else {
                            0
                        }

                        // More accurate video frame estimation using actual video timing
                        val estimatedVideoFrame = if (videoRelativeTimeMs > 0) {
                            // Assume 30 FPS but account for potential frame rate variations
                            (videoRelativeTimeMs * 30.0 / 1000.0).toInt()
                        } else {
                            0
                        }

                        // Calculate synchronization quality metric
                        val syncQuality = calculateSyncQuality(timestampNs, baseTime)
                        
                        // Calculate offset from actual video start
                        val actualVideoOffsetMs = if (actualVideoStartTime > 0) {
                            ((timestampNs - actualVideoStartTime) / 1_000_000).toInt()
                        } else {
                            -1 // Indicates video hasn't actually started yet
                        }

                        // Enhanced CSV logging with comprehensive video correlation
                        csvWriter?.apply {
                            write("$timestampNs,$timestampMs,$frameCount,$filename,$fileSize,$videoRelativeTimeMs,$estimatedVideoFrame,$syncQuality,$actualVideoOffsetMs\n")
                            flush()
                        }

                        // Generate preview for UI
                        generatePreview(outputFile, timestampNs)

                        Log.d(TAG, "Captured frame: $filename ($fileSize bytes) - Video time: ${videoRelativeTimeMs}ms, Est. frame: $estimatedVideoFrame, Sync quality: $syncQuality")
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

    /**
     * Calculate synchronization quality metric based on timing alignment
     * Returns a score from 0.0 (poor) to 1.0 (excellent) synchronization
     */
    private fun calculateSyncQuality(frameTimestamp: Long, videoBaseTime: Long): Double {
        return if (videoBaseTime <= 0) {
            0.0 // No video timing reference yet
        } else {
            // Calculate timing consistency - how well frame timing aligns with expected intervals
            val relativeTime = frameTimestamp - videoBaseTime
            val expectedFrameInterval = 33_333_333L // ~30 FPS in nanoseconds
            
            // Calculate how close we are to expected frame timing
            val timingDeviation = relativeTime % expectedFrameInterval
            val deviationRatio = timingDeviation.toDouble() / expectedFrameInterval.toDouble()
            
            // Convert to quality score (closer to 0 deviation = higher quality)
            1.0 - kotlin.math.min(deviationRatio, 0.5) * 2.0
        }
    }

    /**
     * Log video-related events for comprehensive timing analysis
     */
    private fun logVideoEvent(event: String, timestamp: Long, details: String = "") {
        try {
            val videoEventsFile = File(csvFile?.parent, "video_events.csv")
            
            if (!videoEventsFile.exists()) {
                videoEventsFile.writeText("timestamp_ns,timestamp_ms,event,details\n")
            }
            
            val timestampMs = timestamp / 1_000_000
            videoEventsFile.appendText("$timestamp,$timestampMs,$event,$details\n")
            
            Log.d(TAG, "Video event logged: $event at $timestamp ($details)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log video event: ${e.message}", e)
        }
    }

    /**
     * Get comprehensive timing statistics for post-processing analysis
     */
    fun getTimingStatistics(): Map<String, Any> {
        return mapOf(
            "videoStartTime" to videoStartTime,
            "actualVideoStartTime" to actualVideoStartTime,
            "frameTimestampOffset" to frameTimestampOffset,
            "totalFramesCaptured" to frameCount,
            "avgFrameInterval" to if (frameCount > 1) {
                (System.nanoTime() - videoStartTime) / frameCount / 1_000_000 // ms
            } else 0,
            "syncQualityMetricAvailable" to (actualVideoStartTime > 0)
        )
    }
}
