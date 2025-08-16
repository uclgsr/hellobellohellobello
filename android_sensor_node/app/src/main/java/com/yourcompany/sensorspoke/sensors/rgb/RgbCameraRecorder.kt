package com.yourcompany.sensorspoke.sensors.rgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yourcompany.sensorspoke.sensors.SensorRecorder
import com.yourcompany.sensorspoke.utils.TimeManager
import com.yourcompany.sensorspoke.utils.PreviewBus
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RgbCameraRecorder using CameraX to record a 1080p MP4 and capture high-res JPEGs with
 * nanosecond timestamps in filenames.
 */
class RgbCameraRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
) : SensorRecorder {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var lastPreviewNs: Long = 0L

    override suspend fun start(sessionDir: File) {
        // Ensure directories
        val rgbDir = sessionDir
        if (!rgbDir.exists()) rgbDir.mkdirs()
        val framesDir = File(rgbDir, "frames").apply { mkdirs() }
        val videoFile = File(rgbDir, "video.mp4")

        val provider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider = provider

        // Build Recorder for 1080p
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // ImageCapture for high-res stills
        imageCapture = ImageCapture.Builder()
            .setTargetRotation(0)
            .setJpegQuality(95)
            .build()

        // Unbind then bind
        provider.unbindAll()
        val useCases = mutableListOf<androidx.camera.core.UseCase>()
        imageCapture?.let { useCases.add(it) }
        videoCapture?.let { useCases.add(it) }
        provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())

        // Start video recording
        val outputOpts = FileOutputOptions.Builder(videoFile).build()
        recording = videoCapture!!.output
            .prepareRecording(context, outputOpts)
            .start(ContextCompat.getMainExecutor(context)) { /* events ignored */ }

        // Start still capture loop throttled to ~2 FPS for preview + file archival
        scope.launch {
            while (isActive) {
                val ts = TimeManager.nowNanos()
                val outFile = File(framesDir, "frame_${ts}.jpg")
                val output = ImageCapture.OutputFileOptions.Builder(outFile).build()
                val ic = imageCapture ?: break
                ic.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exception: ImageCaptureException) {
                        // Keep loop running on errors
                    }
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        // Build a downsampled, low-quality JPEG preview and emit
                        val now = TimeManager.nowNanos()
                        // throttle to ~2 FPS
                        if (now - lastPreviewNs >= 500_000_000L) {
                            lastPreviewNs = now
                            runCatching {
                                val bmp = BitmapFactory.decodeFile(outFile.absolutePath) ?: return@runCatching
                                val w = 320
                                val h = 240
                                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                                val baos = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 40, baos)
                                val bytes = baos.toByteArray()
                                PreviewBus.emit(bytes, now)
                                try { baos.close() } catch (_: Exception) {}
                                if (scaled != bmp) {
                                    bmp.recycle()
                                }
                                scaled.recycle()
                            }
                        }
                    }
                })
                // 2 FPS loop (500ms)
                delay(500)
            }
        }
    }

    override suspend fun stop() {
        try { recording?.stop() } catch (_: Exception) {}
        recording = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageCapture = null
        videoCapture = null
        scope.coroutineContext[Job]?.cancel()
        executor.shutdown()
    }
}
