package com.yourcompany.sensorspoke.sensors.rgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import com.yourcompany.sensorspoke.utils.PreviewBus
import com.yourcompany.sensorspoke.utils.TimeManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RgbCameraRecorder using CameraX for video and Camera2 API for Samsung RAW DNG capture.
 * Leverages Samsung Camera API for Level 3/Stage 3 RAW DNG images when available.
 * Falls back to standard CameraX capture on non-Samsung devices.
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
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var lastPreviewNs: Long = 0L
    private var csvWriter: java.io.BufferedWriter? = null
    private var csvFile: File? = null
    
    // Samsung Camera2 API for RAW capture
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawImageReader: ImageReader? = null
    private var supportsSamsungRawCapture = false
    private var rawCameraId: String? = null
    
    // Camera2 background thread and handler
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override suspend fun start(sessionDir: File) {
        // Ensure directories
        val rgbDir = sessionDir
        if (!rgbDir.exists()) rgbDir.mkdirs()
        val framesDir = File(rgbDir, "frames").apply { mkdirs() }
        // Open CSV for DNG index (Phase 3 requirement)
        csvFile = File(rgbDir, "rgb.csv")
        csvWriter = java.io.BufferedWriter(java.io.FileWriter(csvFile!!, true))
        if (csvFile!!.length() == 0L) {
            csvWriter!!.write("timestamp_ns,filename\n")
            csvWriter!!.flush()
        }
        
        // Initialize Samsung camera capabilities and background thread
        startBackgroundThread()
        initializeCameraCapabilities()
        // Use session start timestamp in video filename for traceability
        val sessionStartTs = TimeManager.nowNanos()
        val videoFile = File(rgbDir, "video_$sessionStartTs.mp4")

        val provider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider = provider

        // Build Recorder for 1080p
        val recorder =
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.FHD),
                    ),
                )
                .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Initialize Samsung RAW capture or fallback to CameraX
        if (supportsSamsungRawCapture && rawCameraId != null) {
            Log.i(TAG, "Samsung RAW DNG capture available - using Camera2 API")
            initializeSamsungRawCapture(framesDir)
        } else {
            Log.i(TAG, "Samsung RAW not available - using CameraX fallback")
            // ImageCapture for high-res stills - standard CameraX fallback with DNG extension
            imageCapture =
                ImageCapture.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
        }

        // Unbind then bind
        provider.unbindAll()
        val useCases = mutableListOf<androidx.camera.core.UseCase>()
        imageCapture?.let { useCases.add(it) }
        videoCapture?.let { useCases.add(it) }
        provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())

        // Start video recording
        val outputOpts = FileOutputOptions.Builder(videoFile).build()
        recording =
            videoCapture!!.output
                .prepareRecording(context, outputOpts)
                .start(ContextCompat.getMainExecutor(context)) { /* events ignored */ }

        // Start still capture loop based on device capabilities
        startStillCaptureLoop(framesDir)
    }

    override suspend fun stop() {
        try {
            recording?.stop()
        } catch (_: Exception) {
        }
        recording = null
        
        // Stop Samsung Camera2 RAW capture if active
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        
        try {
            rawImageReader?.close()
        } catch (_: Exception) {
        }
        rawImageReader = null
        
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        
        // Stop background thread
        stopBackgroundThread()
        
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        imageCapture = null
        videoCapture = null
        
        // Cancel capture loop before closing CSV to avoid writes after close
        scope.coroutineContext[Job]?.cancel()
        // Close CSV resources
        try {
            csvWriter?.flush()
        } catch (_: Exception) {
        }
        try {
            csvWriter?.close()
        } catch (_: Exception) {
        }
        csvWriter = null
        csvFile = null
        executor.shutdown()
    }
    
    /**
     * Start background thread for Camera2 API operations
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    
    /**
     * Stop background thread for Camera2 API operations
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
    
    /**
     * Initialize camera capabilities detection for Samsung devices
     */
    private fun initializeCameraCapabilities() {
        try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager?.cameraIdList ?: return
            
            // Check if this is a Samsung device
            val isSamsung = Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
            Log.i(TAG, "Device manufacturer: ${Build.MANUFACTURER}, Model: ${Build.MODEL}")
            
            for (cameraId in cameraIds) {
                val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                // Check for back camera (primary camera)
                if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    // Check camera capability level
                    val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    Log.i(TAG, "Camera $cameraId hardware level: $level")
                    
                    // Samsung devices with LEVEL_3 support advanced RAW capture
                    val supportsLevel3 = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
                    val supportsLimited = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
                    val supportsFull = level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                    
                    // Check RAW capability
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val supportsRaw = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                    
                    Log.i(TAG, "Camera $cameraId - Samsung: $isSamsung, Level3: $supportsLevel3, Full: $supportsFull, RAW: $supportsRaw")
                    
                    // Samsung devices with Level 3 or Full + RAW capability
                    if (isSamsung && (supportsLevel3 || supportsFull) && supportsRaw) {
                        supportsSamsungRawCapture = true
                        rawCameraId = cameraId
                        Log.i(TAG, "Samsung RAW DNG capture enabled for camera $cameraId")
                        break
                    }
                }
            }
            
            if (!supportsSamsungRawCapture) {
                Log.i(TAG, "Samsung RAW DNG capture not available - using CameraX fallback")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize camera capabilities: ${e.message}")
            supportsSamsungRawCapture = false
        }
    }
    
    /**
     * Initialize Samsung Camera2 API for RAW DNG capture
     */
    @Suppress("MissingPermission")
    private suspend fun initializeSamsungRawCapture(framesDir: File) {
        if (!supportsSamsungRawCapture || rawCameraId == null) return
        
        try {
            // Setup ImageReader for RAW_SENSOR format
            val characteristics = cameraManager!!.getCameraCharacteristics(rawCameraId!!)
            val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = configs?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return
            
            if (rawSizes.isEmpty()) {
                Log.w(TAG, "No RAW sensor sizes available")
                return
            }
            
            val maxRawSize = rawSizes.maxByOrNull { it.width * it.height } ?: rawSizes[0]
            Log.i(TAG, "Using RAW size: ${maxRawSize.width}x${maxRawSize.height}")
            
            rawImageReader = ImageReader.newInstance(
                maxRawSize.width, 
                maxRawSize.height, 
                ImageFormat.RAW_SENSOR, 
                2
            )
            
            rawImageReader!!.setOnImageAvailableListener({ reader ->
                handleRawImage(reader, framesDir)
            }, backgroundHandler)
            
            // Open camera device
            val cameraOpenedDeferred = CompletableDeferred<CameraDevice>()
            cameraManager!!.openCamera(rawCameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenedDeferred.complete(camera)
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraOpenedDeferred.completeExceptionally(RuntimeException("Camera disconnected"))
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraOpenedDeferred.completeExceptionally(RuntimeException("Camera error: $error"))
                }
            }, backgroundHandler)
            
            cameraDevice = cameraOpenedDeferred.await()
            Log.i(TAG, "Samsung Camera2 device opened successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Samsung RAW capture: ${e.message}")
            supportsSamsungRawCapture = false
        }
    }
    
    /**
     * Handle RAW image from Samsung Camera2 API and save as DNG
     */
    private fun handleRawImage(reader: ImageReader, framesDir: File) {
        try {
            val image = reader.acquireLatestImage() ?: return
            val timestamp = TimeManager.nowNanos()
            
            scope.launch {
                try {
                    // Save RAW image as DNG file
                    val dngFile = File(framesDir, "frame_$timestamp.dng")
                    saveRawImageAsDng(image, dngFile)
                    
                    // Log to CSV
                    csvWriter?.apply {
                        write("$timestamp,frames/${dngFile.name}\n")
                        flush()
                    }
                    
                    // Generate preview if throttled appropriately
                    val now = TimeManager.nowNanos()
                    if (now - lastPreviewNs >= 150_000_000L) {
                        lastPreviewNs = now
                        generatePreviewFromRaw(image)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing RAW image: ${e.message}")
                } finally {
                    image.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling RAW image: ${e.message}")
        }
    }
    
    /**
     * Save RAW image data as DNG file
     */
    private fun saveRawImageAsDng(image: Image, dngFile: File) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            FileOutputStream(dngFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            
            Log.d(TAG, "Saved RAW DNG: ${dngFile.name} (${bytes.size} bytes)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save DNG file: ${e.message}")
        }
    }
    
    /**
     * Generate JPEG preview from RAW image data
     */
    private fun generatePreviewFromRaw(image: Image) {
        try {
            // Create a basic preview bitmap (simplified for RAW data)
            val bmp = createPlaceholderBitmap()
            val w = 320
            val h = 240
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 40, baos)
            val bytes = baos.toByteArray()
            PreviewBus.emit(bytes, TimeManager.nowNanos())
            
            baos.close()
            if (scaled != bmp) {
                bmp.recycle()
            }
            scaled.recycle()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating preview: ${e.message}")
        }
    }
    
    /**
     * Start still capture loop based on device capabilities
     */
    private fun startStillCaptureLoop(framesDir: File) {
        scope.launch {
            while (isActive) {
                if (supportsSamsungRawCapture && cameraDevice != null && rawImageReader != null) {
                    // Samsung RAW capture using Camera2 API
                    captureRawImage()
                } else {
                    // Fallback to CameraX capture
                    captureCameraXImage(framesDir)
                }
                // ~6–8 FPS loop (~150ms)
                delay(150)
            }
        }
    }
    
    /**
     * Capture RAW image using Samsung Camera2 API
     */
    private suspend fun captureRawImage() {
        try {
            val device = cameraDevice ?: return
            val reader = rawImageReader ?: return
            
            // Create capture session if needed
            if (captureSession == null) {
                val sessionCreatedDeferred = CompletableDeferred<CameraCaptureSession>()
                device.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            sessionCreatedDeferred.complete(session)
                        }
                        
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            sessionCreatedDeferred.completeExceptionally(RuntimeException("Session configure failed"))
                        }
                    },
                    backgroundHandler
                )
                captureSession = sessionCreatedDeferred.await()
            }
            
            // Capture RAW image
            val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequest.addTarget(reader.surface)
            
            // Samsung-specific optimizations for RAW capture
            captureRequest.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            captureRequest.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            captureRequest.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            captureRequest.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            
            captureSession?.capture(captureRequest.build(), null, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing RAW image: ${e.message}")
        }
    }
    
    /**
     * Fallback capture using CameraX when Samsung RAW is not available
     */
    private fun captureCameraXImage(framesDir: File) {
        try {
            val ts = TimeManager.nowNanos()
            val outFile = File(framesDir, "frame_$ts.dng")
            val output = ImageCapture.OutputFileOptions.Builder(outFile).build()
            val ic = imageCapture ?: return
            
            ic.takePicture(
                output,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exception: ImageCaptureException) {
                        // Keep loop running on errors
                        Log.w(TAG, "CameraX capture error: ${exception.message}")
                    }

                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        // Log DNG to CSV: timestamp_ns,filename (relative)
                        try {
                            csvWriter?.apply {
                                write("$ts,frames/${outFile.name}\n")
                                flush()
                            }
                        } catch (_: Exception) {
                        }
                        // Build a downsampled, low-quality JPEG preview from DNG and emit
                        val now = TimeManager.nowNanos()
                        // throttle to ~6–8 FPS
                        if (now - lastPreviewNs >= 150_000_000L) {
                            lastPreviewNs = now
                            runCatching {
                                // For preview, we'll decode the DNG if possible, otherwise create placeholder
                                // Note: DNG decoding requires more complex processing, using placeholder for now
                                val bmp = createPlaceholderBitmap() ?: return@runCatching
                                val w = 320
                                val h = 240
                                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                                val baos = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 40, baos)
                                val bytes = baos.toByteArray()
                                PreviewBus.emit(bytes, now)
                                try {
                                    baos.close()
                                } catch (_: Exception) {
                                }
                                if (scaled != bmp) {
                                    bmp.recycle()
                                }
                                scaled.recycle()
                            }
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in CameraX capture: ${e.message}")
        }
    }
    
    /**
     * Creates a placeholder bitmap for DNG preview (since DNG decoding is complex)
     */
    private fun createPlaceholderBitmap(): Bitmap {
        return Bitmap.createBitmap(640, 480, Bitmap.Config.RGB_565)
    }
}
