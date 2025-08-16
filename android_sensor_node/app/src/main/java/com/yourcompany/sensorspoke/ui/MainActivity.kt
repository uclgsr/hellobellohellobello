package com.yourcompany.sensorspoke.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.sensors.gsr.ShimmerRecorder
import com.yourcompany.sensorspoke.sensors.rgb.RgbCameraRecorder
import com.yourcompany.sensorspoke.sensors.thermal.ThermalCameraRecorder
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private var controller: RecordingController? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Simple UI with Start/Stop buttons
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val startBtn = Button(this).apply { text = "Start Recording" }
        val stopBtn = Button(this).apply { text = "Stop Recording" }
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)

        startBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                startRecording()
            }
        }
        stopBtn.setOnClickListener { stopRecording() }
    }

    private fun ensureController(): RecordingController {
        val existing = controller
        if (existing != null) return existing
        val c = RecordingController(applicationContext)
        // Register recorders
        c.register("rgb", RgbCameraRecorder(applicationContext, this))
        c.register("thermal", ThermalCameraRecorder())
        c.register("gsr", ShimmerRecorder())
        controller = c
        return c
    }

    private fun startRecording() {
        lifecycleScope.launch {
            try {
                ensureController().startSession()
                Toast.makeText(this@MainActivity, "Recording started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopRecording() {
        lifecycleScope.launch {
            try {
                controller?.stopSession()
                Toast.makeText(this@MainActivity, "Recording stopped", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
