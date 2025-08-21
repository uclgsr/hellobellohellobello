package com.yourcompany.sensorspoke.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.sensors.gsr.ShimmerRecorder
import com.yourcompany.sensorspoke.sensors.rgb.RgbCameraRecorder
import com.yourcompany.sensorspoke.sensors.thermal.ThermalCameraRecorder
import com.yourcompany.sensorspoke.service.RecordingService
import com.yourcompany.sensorspoke.ui.adapters.MainPagerAdapter
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    private var controller: RecordingController? = null
    private var viewPager: ViewPager2? = null
    private var tabLayout: TabLayout? = null
    private var btnStartRecording: Button? = null
    private var btnStopRecording: Button? = null
    private var rootLayout: ViewGroup? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    private val controlReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val action = intent?.action ?: return
                when (action) {
                    RecordingService.ACTION_START_RECORDING -> {
                        val sessionId = intent.getStringExtra(RecordingService.EXTRA_SESSION_ID)
                        lifecycleScope.launch {
                            try {
                                ensureController().startSession(sessionId)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    RecordingService.ACTION_STOP_RECORDING -> {
                        lifecycleScope.launch { runCatching { controller?.stopSession() } }
                    }

                    RecordingService.ACTION_FLASH_SYNC -> {
                        val ts = intent.getLongExtra(RecordingService.EXTRA_FLASH_TS_NS, 0L)
                        showFlashOverlay()
                        logFlashEvent(ts)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        rootLayout = findViewById<ViewGroup>(android.R.id.content)

        // Setup ViewPager with fragments
        setupViewPager()

        // Setup button handlers
        setupButtons()

        // Ensure background service for NSD + TCP server is running (skip during unit tests)
        if (!isRunningUnderTest()) {
            val svcIntent = Intent(this, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        }
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        viewPager?.adapter = adapter

        // Connect TabLayout with ViewPager2
        tabLayout?.let { tabLayout ->
            viewPager?.let { viewPager ->
                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = MainPagerAdapter.TAB_TITLES[position]
                }.attach()
            }
        }
    }

    private fun setupButtons() {
        btnStartRecording?.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                startRecording()
            }
        }

        btnStopRecording?.setOnClickListener {
            stopRecording()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter =
            IntentFilter().apply {
                addAction(RecordingService.ACTION_START_RECORDING)
                addAction(RecordingService.ACTION_STOP_RECORDING)
                addAction(RecordingService.ACTION_FLASH_SYNC)
            }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(controlReceiver) }
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

    private fun showFlashOverlay() {
        val parent = rootLayout ?: return
        val flash =
            View(this).apply {
                setBackgroundColor(Color.WHITE)
                layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                alpha = 1f
            }
        parent.addView(flash)
        flash.postDelayed({ parent.removeView(flash) }, 150)
    }

    private fun logFlashEvent(tsNs: Long) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "flash_sync_events.csv")
            if (!f.exists()) {
                f.writeText("timestamp_ns\n")
            }
            f.appendText("$tsNs\n")
        } catch (_: Exception) {
        }
    }

    private fun isRunningUnderTest(): Boolean {
        return try {
            Class.forName("org.robolectric.Robolectric")
            true
        } catch (_: Throwable) {
            false
        }
    }
}
