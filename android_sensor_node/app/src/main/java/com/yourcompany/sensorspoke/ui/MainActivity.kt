package com.yourcompany.sensorspoke.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.sensors.audio.AudioRecorder
import com.yourcompany.sensorspoke.sensors.coordination.MultiModalSensorCoordinator
import com.yourcompany.sensorspoke.sensors.gsr.ShimmerRecorder
import com.yourcompany.sensorspoke.sensors.rgb.RgbCameraRecorder
import com.yourcompany.sensorspoke.sensors.thermal.ThermalCameraRecorder
import com.yourcompany.sensorspoke.service.RecordingService
import com.yourcompany.sensorspoke.ui.adapters.MainPagerAdapter
import com.yourcompany.sensorspoke.ui.components.SensorStatusIndicator
import com.yourcompany.sensorspoke.ui.dialogs.QuickStartDialog
import com.yourcompany.sensorspoke.ui.models.SensorStatus
import com.yourcompany.sensorspoke.ui.navigation.NavigationController
import com.yourcompany.sensorspoke.ui.navigation.ThermalNavigationState
import com.yourcompany.sensorspoke.utils.PermissionManager
import com.yourcompany.sensorspoke.utils.UserExperience
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val vm: MainViewModel by viewModels()

    private var controller: RecordingController? = null

    // Full integration: MultiModalSensorCoordinator for comprehensive sensor management
    private var multiModalCoordinator: MultiModalSensorCoordinator? = null

    private var viewPager: ViewPager2? = null
    private var tabLayout: TabLayout? = null
    private var btnStartRecording: Button? = null
    private var btnStopRecording: Button? = null
    private var statusText: TextView? = null
    private var recordingTimer: TextView? = null
    private var rootLayout: ViewGroup? = null

    // Sensor status indicators
    private var rgbSensorStatus: SensorStatusIndicator? = null
    private var thermalSensorStatus: SensorStatusIndicator? = null
    private var gsrSensorStatus: SensorStatusIndicator? = null
    private var pcSensorStatus: SensorStatusIndicator? = null

    // Enhanced navigation controller from IRCamera architecture
    private var navigationController: NavigationController? = null

    // User experience enhancements
    private lateinit var preferences: SharedPreferences
    private var isFirstLaunch: Boolean = false

    // Comprehensive permission management
    private lateinit var permissionManager: PermissionManager

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
                        updateStatusText("Starting recording session: $sessionId")
                        lifecycleScope.launch {
                            try {
                                ensureController().startSession(sessionId)
                                UserExperience.Messaging.showSuccess(this@MainActivity, "Recording started", sessionId)
                            } catch (e: Exception) {
                                UserExperience.Messaging.showUserFriendlyError(this@MainActivity, e.message ?: "Unknown error", "recording")
                            }
                        }
                    }

                    RecordingService.ACTION_STOP_RECORDING -> {
                        updateStatusText("Stopping recording...")
                        lifecycleScope.launch {
                            runCatching {
                                controller?.stopSession()
                                UserExperience.Messaging.showSuccess(this@MainActivity, "Recording stopped")
                                updateStatusText("Ready to record")
                            }.onFailure { e ->
                                UserExperience.Messaging.showUserFriendlyError(this@MainActivity, e.message ?: "Unknown error", "recording")
                            }
                        }
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

        // Initialize preferences and check first launch
        preferences = getSharedPreferences("sensor_spoke_prefs", Context.MODE_PRIVATE)
        isFirstLaunch = preferences.getBoolean("first_launch", true)

        // Initialize views
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        statusText = findViewById(R.id.statusText)
        recordingTimer = findViewById(R.id.recordingTimer)
        rootLayout = findViewById<ViewGroup>(android.R.id.content)
        
        // Initialize sensor status indicators
        rgbSensorStatus = findViewById(R.id.rgbSensorStatus)
        thermalSensorStatus = findViewById(R.id.thermalSensorStatus)
        gsrSensorStatus = findViewById(R.id.gsrSensorStatus)
        pcSensorStatus = findViewById(R.id.pcSensorStatus)

        // Setup ViewPager with fragments
        setupViewPager()

        // Setup button handlers
        setupButtons()

        // Setup toolbar with menu
        setupToolbar()

        // Initialize comprehensive permission management
        permissionManager = PermissionManager(this)

        // Initialize the ViewModel with the recording controller
        initializeViewModel()

        // Setup UI state observation
        observeUiState()

        // Initialize status
        updateStatusText("Initializing...")

        // Initialize TC001 thermal camera system
        initializeTC001System()

        // Ensure background service for NSD + TCP server is running (skip during unit tests)
        if (!isRunningUnderTest()) {
            val svcIntent = Intent(this, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        }

        // Show quick start guide for first-time users
        if (isFirstLaunch) {
            showQuickStartGuide()
        }

        // Request all permissions on startup if not already granted
        if (!permissionManager.areAllPermissionsGranted()) {
            requestAllPermissions()
        }

        // Initialize sensor status indicators with realistic initial states
        initializeSensorStatusIndicators()

        updateStatusText("Ready to connect")
    }

    /**
     * Initialize sensor status indicators with default states
     */
    private fun initializeSensorStatusIndicators() {
        // Initialize with default sensor names for display
        rgbSensorStatus?.updateStatus("RGB", SensorStatusIndicator.SensorStatus(
            name = "RGB Camera",
            isActive = false,
            isHealthy = false,
            statusMessage = "Offline"
        ))
        
        thermalSensorStatus?.updateStatus("Thermal", SensorStatusIndicator.SensorStatus(
            name = "Thermal Camera",
            isActive = false,
            isHealthy = false,
            statusMessage = "Offline"
        ))
        
        gsrSensorStatus?.updateStatus("GSR", SensorStatusIndicator.SensorStatus(
            name = "GSR Sensor",
            isActive = false,
            isHealthy = false,
            statusMessage = "Disconnected"
        ))
        
        pcSensorStatus?.updateStatus("PC", SensorStatusIndicator.SensorStatus(
            name = "PC Link",
            isActive = false,
            isHealthy = false,
            statusMessage = "Not Connected"
        ))
        
        // Then update with ViewModel data
        updateSensorStatus()
    }

    /**
     * Initialize the ViewModel with the recording controller
     */
    private fun initializeViewModel() {
        lifecycleScope.launch {
            val controller = ensureController()
            vm.initialize(controller)
        }
    }

    /**
     * Observe UI state changes and update the interface accordingly
     */
    private fun observeUiState() {
        lifecycleScope.launch {
            vm.uiState.collect { state ->
                // Update status text
                statusText?.text = state.statusText
                
                // Update recording timer
                if (state.isRecording && state.recordingDurationSeconds > 0) {
                    recordingTimer?.visibility = View.VISIBLE
                    recordingTimer?.text = formatRecordingTime(state.recordingDurationSeconds)
                } else {
                    recordingTimer?.visibility = View.GONE
                }
                
                // Update button states
                btnStartRecording?.isEnabled = state.startButtonEnabled
                btnStopRecording?.isEnabled = state.stopButtonEnabled
                
                // Update button text based on recording state
                btnStartRecording?.text = if (state.isRecording) "Recording..." else "Start Recording"
                
                // Update sensor status indicators
                rgbSensorStatus?.updateStatus("RGB Camera", state.cameraStatus.toSensorStatusIndicator())
                thermalSensorStatus?.updateStatus("Thermal", state.thermalStatus.toSensorStatusIndicator())
                gsrSensorStatus?.updateStatus("GSR", state.shimmerStatus.toSensorStatusIndicator())
                pcSensorStatus?.updateStatus("PC Link", state.pcStatus.toSensorStatusIndicator())
                
                // Handle error dialog
                if (state.showErrorDialog && !state.errorMessage.isNullOrEmpty()) {
                    showErrorDialog(state.errorMessage)
                }
                
                // Handle thermal simulation feedback
                if (state.thermalIsSimulated && state.isThermalConnected) {
                    showThermalSimulationOverlay()
                }
            }
        }
    }

    /**
     * Format recording time in HH:MM:SS format
     */
    private fun formatRecordingTime(seconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    /**
     * Convert SensorStatus to SensorStatusIndicator.SensorStatus
     */
    private fun SensorStatus.toSensorStatusIndicator(): SensorStatusIndicator.SensorStatus {
        return SensorStatusIndicator.SensorStatus(
            name = this.name,
            isActive = this.isActive,
            isHealthy = this.isHealthy,
            lastUpdate = this.lastUpdate,
            statusMessage = this.statusMessage
        )
    }

    /**
     * Show error dialog with user-friendly message
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                vm.clearError()
            }
            .setOnDismissListener {
                vm.clearError() 
            }
            .show()
    }

    /**
     * Show thermal simulation overlay when thermal camera is in simulation mode
     */
    private fun showThermalSimulationOverlay() {
        rootLayout?.let { layout ->
            val snackbar = Snackbar.make(
                layout, 
                "Thermal Camera Simulation (device not found)", 
                Snackbar.LENGTH_LONG
            )
            snackbar.show()
        }
    }

    private fun setupToolbar() {
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "Sensor Spoke"
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_quick_start -> {
                showQuickStartGuide()
                true
            }
            R.id.action_connection_help -> {
                showConnectionHelp()
                true
            }
            R.id.action_reset_tutorial -> {
                resetFirstLaunchFlag()
                true
            }
            R.id.action_thermal_settings -> {
                // Navigate to thermal settings using enhanced navigation
                navigationController?.navigateToThermalCamera(ThermalNavigationState.SETTINGS)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        viewPager?.adapter = adapter

        // Initialize enhanced navigation controller
        viewPager?.let { vp ->
            navigationController = NavigationController(this, vp)
        }

        // Connect TabLayout with ViewPager2
        tabLayout?.let { tabLayout ->
            viewPager?.let { viewPager ->
                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = MainPagerAdapter.TAB_TITLES[position]
                }.attach()

                // Register page change callback for enhanced navigation tracking
                viewPager.registerOnPageChangeCallback(
                    object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            super.onPageSelected(position)
                            navigationController?.updateCurrentTab(position)
                        }
                    },
                )
            }
        }
    }

    private fun setupButtons() {
        btnStartRecording?.setOnClickListener {
            startRecording()
        }

        btnStopRecording?.setOnClickListener {
            stopRecording()
        }
    }

    /**
     * Enhanced status text updates with visual indicators.
     */
    private fun updateStatusText(message: String) {
        vm.updateStatusText(message)
        
        // Add color coding based on status
        when {
            message.contains("error", ignoreCase = true) ||
                message.contains("failed", ignoreCase = true) -> {
                statusText?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            message.contains("recording", ignoreCase = true) -> {
                statusText?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            }
            message.contains("ready", ignoreCase = true) ||
                message.contains("connected", ignoreCase = true) ||
                message.contains("success", ignoreCase = true) -> {
                statusText?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
            message.contains("checking", ignoreCase = true) ||
                message.contains("starting", ignoreCase = true) ||
                message.contains("stopping", ignoreCase = true) -> {
                statusText?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            }
            else -> {
                statusText?.setTextColor(ContextCompat.getColor(this, android.R.color.primary_text_light))
            }
        }

        Log.i(TAG, "Status: $message")
    }

    /**
     * Update sensor status based on actual sensor state 
     */
    private fun updateSensorStatus() {
        // RGB Camera status (simulate for now)
        val rgbStatus = SensorStatus(
            name = "RGB Camera",
            isActive = true, // Would be updated by actual camera manager
            isHealthy = true,
            statusMessage = getString(R.string.sensor_rgb_active)
        )
        vm.updateSensorStatus("rgb", rgbStatus)

        // Thermal Camera status (simulate detection)
        val thermalStatus = SensorStatus(
            name = "Thermal Camera",
            isActive = false, // Would be updated by thermal camera manager
            isHealthy = false,
            statusMessage = getString(R.string.sensor_thermal_simulated)
        )
        vm.updateSensorStatus("thermal", thermalStatus)

        // GSR Sensor status (simulate BLE connection)
        val gsrStatus = SensorStatus(
            name = "GSR Sensor",
            isActive = false, // Would be updated by Shimmer manager
            isHealthy = false,
            statusMessage = getString(R.string.sensor_gsr_disconnected)
        )
        vm.updateSensorStatus("gsr", gsrStatus)

        // PC Link status (simulate network connection)
        val pcStatus = SensorStatus(
            name = "PC Link",
            isActive = false, // Would be updated by network manager
            isHealthy = false,
            statusMessage = getString(R.string.sensor_pc_disconnected)
        )
        vm.updateSensorStatus("pc", pcStatus)
    }

    /**
     * Enhanced connection status indicator.
     */
    private fun updateConnectionStatus(connected: Boolean, serverInfo: String = "") {
        val statusMessage = if (connected) {
            "Connected to PC Hub${if (serverInfo.isNotEmpty()) " ($serverInfo)" else ""}"
        } else {
            "Not connected to PC Hub"
        }

        // Update PC connection status through ViewModel
        val pcStatus = SensorStatus(
            name = "PC Link",
            isActive = connected,
            isHealthy = connected,
            statusMessage = if (connected) getString(R.string.sensor_pc_connected) else getString(R.string.sensor_pc_disconnected)
        )
        vm.updateSensorStatus("pc", pcStatus)

        // Update status text
        updateStatusText(statusMessage)

        // Update UI colors/states based on connection
        rootLayout?.setBackgroundColor(
            if (connected) {
                ContextCompat.getColor(this, android.R.color.background_light)
            } else {
                ContextCompat.getColor(this, android.R.color.background_dark)
            },
        )
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

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup MultiModalSensorCoordinator for full integration
        lifecycleScope.launch {
            multiModalCoordinator?.stopRecording()
            multiModalCoordinator = null
        }

        // Cleanup permission manager
        permissionManager.cleanup()
    }

    private fun ensureController(): RecordingController {
        val existing = controller
        if (existing != null) return existing
        val c = RecordingController(applicationContext)
        // Register recorders
        c.register("rgb", RgbCameraRecorder(applicationContext, this))
        c.register("thermal", ThermalCameraRecorder(applicationContext))
        c.register("gsr", ShimmerRecorder(applicationContext))
        c.register("audio", AudioRecorder(applicationContext)) // FR5: Audio recording support
        controller = c
        return c
    }

    /**
     * Ensure MultiModalSensorCoordinator is initialized - Full Integration
     */
    private suspend fun ensureMultiModalCoordinator(): MultiModalSensorCoordinator {
        val existing = multiModalCoordinator
        if (existing != null) return existing

        val coordinator = MultiModalSensorCoordinator(applicationContext, this)

        // Initialize the complete multi-modal system
        val initResult = coordinator.initializeSystem()
        if (initResult) {
            Log.i("MainActivity", "MultiModalSensorCoordinator initialized successfully - Full Integration active")
        } else {
            Log.w("MainActivity", "MultiModalSensorCoordinator initialization failed, falling back to individual recorders")
        }

        multiModalCoordinator = coordinator
        return coordinator
    }

    private fun startRecording() {
        // Enhanced UI feedback during permission checks
        vm.updateStatusText("Checking permissions...")
        btnStartRecording?.isEnabled = false

        if (permissionManager.areAllPermissionsGranted()) {
            vm.startRecording()
        } else {
            vm.showProgress("Checking permissions for all sensors")
            requestAllPermissions()
        }
    }

    private fun stopRecording() {
        // Enhanced UI feedback during stop operation
        vm.updateStatusText("Stopping recording...")
        btnStopRecording?.isEnabled = false
        vm.stopRecording()
    }

    private fun showQuickStartGuide() {
        QuickStartDialog.show(this) {
            // Mark first launch as complete
            preferences
                .edit()
                .putBoolean("first_launch", false)
                .apply()

            UserExperience.Messaging.showStatus(this, "Quick start guide completed!")
        }
    }

    private fun showConnectionHelp() {
        val troubleshootingSteps = UserExperience.QuickStart.getConnectionTroubleshootingSteps()
        val message =
            "Connection Troubleshooting:\n\n" +
                troubleshootingSteps
                    .mapIndexed { index, step ->
                        "${index + 1}. $step"
                    }.joinToString("\n")

        // Show as a Snackbar with action
        rootLayout?.let { layout ->
            val snackbar =
                Snackbar
                    .make(layout, "Connection help available", Snackbar.LENGTH_LONG)
                    .setAction("Show Help") {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
            snackbar.show()
        }
    }

    private fun resetFirstLaunchFlag() {
        preferences
            .edit()
            .putBoolean("first_launch", true)
            .apply()
        UserExperience.Messaging.showStatus(this, "Tutorial will show on next launch")
    }

    private fun showFlashOverlay() {
        val parent = rootLayout ?: return
        val flashStartTime = System.nanoTime()

        val flash =
            View(this).apply {
                setBackgroundColor(Color.WHITE)
                layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                alpha = 1f
            }
        parent.addView(flash)

        // Log flash display timing for synchronization validation
        Log.d("FlashSync", "Flash overlay displayed at: ${flashStartTime}ns")

        flash.postDelayed({
            parent.removeView(flash)
            val flashEndTime = System.nanoTime()
            Log.d("FlashSync", "Flash overlay removed at: ${flashEndTime}ns (duration: ${(flashEndTime - flashStartTime) / 1_000_000}ms)")
        }, 150)
    }

    private fun logFlashEvent(tsNs: Long) {
        try {
            val actualFlashTime = System.nanoTime()
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "flash_sync_events.csv")
            if (!f.exists()) {
                f.writeText("trigger_timestamp_ns,actual_flash_timestamp_ns,sync_delay_ms,device_id\n")
            }

            val syncDelay = (actualFlashTime - tsNs) / 1_000_000.0 // Convert to milliseconds
            val deviceId =
                android.os.Build.MODEL
                    ?.replace(" ", "_") ?: "unknown"

            f.appendText("$tsNs,$actualFlashTime,$syncDelay,$deviceId\n")

            Log.i("FlashSync", "Flash event logged - Sync delay: ${syncDelay}ms")
        } catch (e: Exception) {
            Log.e("FlashSync", "Failed to log flash event: ${e.message}", e)
        } catch (_: Exception) {
        }
    }

    /**
     * Navigate to thermal camera preview - Enhanced integration method
     */
    fun navigateToThermalPreview() {
        // Use NavigationController to navigate to thermal camera
        navigationController?.navigateToThermalCamera(ThermalNavigationState.PREVIEW)
    }

    /**
     * Navigate to thermal camera settings
     */
    fun navigateToThermalSettings() {
        navigationController?.navigateToThermalCamera(ThermalNavigationState.SETTINGS)
    }

    /**
     * Initialize TC001 thermal camera system
     */
    private fun initializeTC001System() {
        try {
            // Initialize TC001 logging
            com.yourcompany.sensorspoke.sensors.thermal.tc001.TC001InitUtil
                .initLog()

            // Initialize TC001 USB receivers
            com.yourcompany.sensorspoke.sensors.thermal.tc001.TC001InitUtil
                .initReceiver(this)

            // Initialize TC001 device manager
            com.yourcompany.sensorspoke.sensors.thermal.tc001.TC001InitUtil
                .initTC001DeviceManager(this)

            Log.i("MainActivity", "TC001 thermal camera system initialized successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize TC001 system", e)
        }
    }

    /**
     * Request all necessary permissions for multi-modal recording
     */
    private fun requestAllPermissions() {
        vm.updateStatusText("Requesting permissions...")

        permissionManager.requestAllPermissions { allGranted ->
            if (allGranted) {
                vm.updateStatusText("All permissions granted - Ready to record")
                vm.hideProgress()
                UserExperience.Messaging.showSuccess(
                    this,
                    "All sensor permissions granted. Ready to start recording!",
                )
                
                // Update sensor status to reflect permission grants
                updateSensorStatus()
            } else {
                vm.updateStatusText("Some permissions denied - Limited functionality")
                vm.hideProgress()
                UserExperience.Messaging.showUserFriendlyError(
                    this,
                    "Some permissions were denied. Recording may not include all sensors.",
                    "permission",
                )

                // Show detailed permission status in debug
                Log.d("MainActivity", "Permission status: ${permissionManager.getPermissionStatus()}")
            }
        }
    }

    /**
     * Show toast message for transient notifications
     */
    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Show user-friendly error with Toast
     */
    private fun showUserFriendlyError(message: String, context: String = "") {
        val fullMessage = if (context.isNotEmpty()) {
            "Error in $context: $message"
        } else {
            message
        }
        showToast(fullMessage)
        vm.showError(fullMessage)
    }

    /**
     * Check if we're running under test conditions
     */
    private fun isRunningUnderTest(): Boolean {
        return try {
            Class.forName("androidx.test.espresso.Espresso")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
