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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.controller.RecordingController
import com.yourcompany.sensorspoke.network.ConnectionManager
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
    private var connectionManager: ConnectionManager? = null

    // Full integration: MultiModalSensorCoordinator for comprehensive sensor management
    private var multiModalCoordinator: MultiModalSensorCoordinator? = null

    // UI Components
    private var viewPager: ViewPager2? = null
    private var tabLayout: TabLayout? = null
    private var btnStartRecording: Button? = null
    private var btnStopRecording: Button? = null
    private var statusText: TextView? = null
    private var recordingTimeText: TextView? = null
    private var rootLayout: ViewGroup? = null

    // Sensor status indicators
    private var rgbStatusIndicator: View? = null
    private var rgbStatusText: TextView? = null
    private var thermalStatusIndicator: View? = null
    private var thermalStatusText: TextView? = null
    private var gsrStatusIndicator: View? = null
    private var gsrStatusText: TextView? = null
    private var pcStatusIndicator: View? = null
    private var pcStatusText: TextView? = null

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
                        vm.updateUiState { copy(statusText = "Starting recording session: $sessionId") }
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
                        vm.updateUiState { copy(statusText = "Stopping recording...") }
                        lifecycleScope.launch {
                            runCatching {
                                controller?.stopSession()
                                UserExperience.Messaging.showSuccess(this@MainActivity, "Recording stopped")
                                vm.updateUiState { copy(statusText = "Ready to record") }
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
        initializeViews()

        // Setup ViewPager with fragments
        setupViewPager()

        // Setup button handlers
        setupButtons()

        // Setup toolbar with menu
        setupToolbar()

        // Initialize comprehensive permission management
        permissionManager = PermissionManager(this)

        // Initialize connection manager
        connectionManager = createConnectionManager()

        // Initialize ViewModel with orchestrator and connection manager
        initializeViewModel()

        // Setup UI state observers
        setupUiStateObservers()

        // Initialize status
        vm.updateUiState { copy(statusText = "Initializing...") }

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

        // Initialize sensor status monitoring
        initializeSensorStatusMonitoring()

        vm.updateUiState { copy(statusText = "Ready to connect") }
    }

    /**
     * Initialize all UI view references
     */
    private fun initializeViews() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        statusText = findViewById(R.id.statusText)
        recordingTimeText = findViewById(R.id.recordingTimeText)
        rootLayout = findViewById<ViewGroup>(android.R.id.content)

        // Initialize sensor status indicators
        rgbStatusIndicator = findViewById(R.id.rgbStatusIndicator)
        rgbStatusText = findViewById(R.id.rgbStatusText)
        thermalStatusIndicator = findViewById(R.id.thermalStatusIndicator)
        thermalStatusText = findViewById(R.id.thermalStatusText)
        gsrStatusIndicator = findViewById(R.id.gsrStatusIndicator)
        gsrStatusText = findViewById(R.id.gsrStatusText)
        pcStatusIndicator = findViewById(R.id.pcStatusIndicator)
        pcStatusText = findViewById(R.id.pcStatusText)
    }

    /**
     * Create and configure connection manager
     */
    private fun createConnectionManager(): ConnectionManager? {
        return try {
            val networkClient = com.yourcompany.sensorspoke.network.NetworkClient(this)
            val manager = ConnectionManager(this, networkClient)

            // Setup connection callbacks
            manager.onConnectionEstablished = { address, port ->
                runOnUiThread {
                    vm.updatePcConnectionStatus(true, "$address:$port")
                    showToast("Connected to PC Hub")
                }
            }

            manager.onConnectionLost = {
                runOnUiThread {
                    vm.updatePcConnectionStatus(false)
                    showToast("Connection to PC Hub lost")
                }
            }

            manager.onConnectionRestored = {
                runOnUiThread {
                    vm.updatePcConnectionStatus(true)
                    showToast("Connection restored")
                }
            }

            manager.onReconnectFailed = {
                runOnUiThread {
                    vm.showError("Failed to reconnect to PC Hub")
                }
            }

            manager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create connection manager", e)
            null
        }
    }

    /**
     * Initialize ViewModel with orchestrator and connection manager
     */
    private fun initializeViewModel() {
        val orchestrator = ensureController()
        connectionManager?.let { connManager ->
            vm.initialize(orchestrator, connManager)
        } ?: run {
            vm.initialize(orchestrator)
        }
    }

    /**
     * Setup UI state observers for reactive updates
     */
    private fun setupUiStateObservers() {
        // Observe main UI state
        lifecycleScope.launch {
            vm.uiState.collect { uiState ->
                updateUI(uiState)
            }
        }

        // Observe error messages for dialogs
        lifecycleScope.launch {
            vm.showErrorDialog.collect { showDialog ->
                if (showDialog) {
                    vm.errorMessage.value?.let { message ->
                        showErrorDialog(message)
                    }
                }
            }
        }

        // Observe sensor status for individual updates
        lifecycleScope.launch {
            vm.sensorStatus.collect { sensorMap ->
                updateSensorStatusIndicators(sensorMap)
            }
        }

        // Observe recording state for button updates
        lifecycleScope.launch {
            vm.recordingState.collect { state ->
                updateButtonsForRecordingState(state)
            }
        }
    }

    /**
     * Update UI based on comprehensive UI state
     */
    private fun updateUI(state: MainViewModel.MainUiState) {
        // Update status text
        statusText?.text = state.statusText
        updateStatusTextColor(state.statusText)

        // Update recording time visibility and text
        if (state.isRecording) {
            recordingTimeText?.visibility = View.VISIBLE
            recordingTimeText?.text = "Recording: ${state.recordingElapsedTime}"
        } else {
            recordingTimeText?.visibility = View.GONE
        }

        // Update button states
        btnStartRecording?.isEnabled = state.startButtonEnabled
        btnStopRecording?.isEnabled = state.stopButtonEnabled

        // Update button text based on recording state
        btnStartRecording?.text = if (state.isRecording) "Recording..." else "Start Recording"

        // Update sensor status indicators
        updateSensorIndicator(rgbStatusIndicator, rgbStatusText, state.isCameraConnected, "RGB")
        updateSensorIndicator(thermalStatusIndicator, thermalStatusText, state.isThermalConnected, "Thermal", state.thermalStatus.isSimulated)
        updateSensorIndicator(gsrStatusIndicator, gsrStatusText, state.isShimmerConnected, "GSR")
        updateSensorIndicator(pcStatusIndicator, pcStatusText, state.isPcConnected, "PC Link")

        // Add thermal simulation indicator
        if (state.thermalStatus.isSimulated) {
            thermalStatusText?.text = "Thermal (Sim)"
        }
    }

    /**
     * Update individual sensor status indicator
     */
    private fun updateSensorIndicator(indicator: View?, textView: TextView?, isConnected: Boolean, label: String, isSimulated: Boolean = false) {
        indicator?.background = ContextCompat.getDrawable(
            this,
            when {
                isConnected && isSimulated -> R.drawable.status_indicator_orange
                isConnected -> R.drawable.status_indicator_green
                else -> R.drawable.status_indicator_red
            },
        )

        textView?.text = when {
            isConnected && isSimulated -> "$label (Sim)"
            isConnected -> label
            else -> label
        }
    }

    /**
     * Update sensor status indicators from sensor map
     */
    private fun updateSensorStatusIndicators(sensorMap: Map<String, MainViewModel.SensorStatus>) {
        sensorMap["rgb"]?.let { status ->
            vm.updateRgbCameraStatus(status.isActive)
        }

        sensorMap["thermal"]?.let { status ->
            vm.updateThermalStatus(status.isActive, status.isSimulated)
        }

        sensorMap["gsr"]?.let { status ->
            vm.updateGsrStatus(status.isActive)
        }
    }

    /**
     * Update button states based on recording state
     */
    private fun updateButtonsForRecordingState(state: MainViewModel.RecordingState) {
        when (state) {
            MainViewModel.RecordingState.IDLE -> {
                btnStartRecording?.isEnabled = permissionManager.areAllPermissionsGranted()
                btnStopRecording?.isEnabled = false
                btnStartRecording?.text = "Start Recording"
            }
            MainViewModel.RecordingState.PREPARING -> {
                btnStartRecording?.isEnabled = false
                btnStopRecording?.isEnabled = false
                btnStartRecording?.text = "Preparing..."
            }
            MainViewModel.RecordingState.RECORDING -> {
                btnStartRecording?.isEnabled = false
                btnStopRecording?.isEnabled = true
                btnStartRecording?.text = "Recording..."
            }
            MainViewModel.RecordingState.STOPPING -> {
                btnStartRecording?.isEnabled = false
                btnStopRecording?.isEnabled = false
                btnStartRecording?.text = "Stopping..."
            }
            MainViewModel.RecordingState.ERROR -> {
                btnStartRecording?.isEnabled = true
                btnStopRecording?.isEnabled = false
                btnStartRecording?.text = "Start Recording"
            }
        }
    }

    /**
     * Initialize sensor status monitoring
     */
    private fun initializeSensorStatusMonitoring() {
        lifecycleScope.launch {
            while (true) {
                try {
                    // Monitor RGB camera status
                    monitorRgbCameraStatus()

                    // Monitor thermal camera status
                    monitorThermalCameraStatus()

                    // Monitor GSR sensor status
                    monitorGsrSensorStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring sensor status", e)
                }

                kotlinx.coroutines.delay(3000) // Check every 3 seconds
            }
        }
    }

    /**
     * Monitor RGB camera status
     */
    private suspend fun monitorRgbCameraStatus() {
        try {
            // Check if camera is available (simplified check)
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraIds = cameraManager.cameraIdList
            val isAvailable = cameraIds.isNotEmpty()

            vm.updateRgbCameraStatus(isAvailable, isAvailable)
        } catch (e: Exception) {
            vm.updateRgbCameraStatus(false)
        }
    }

    /**
     * Monitor thermal camera status
     */
    private suspend fun monitorThermalCameraStatus() {
        try {
            // Check if thermal camera is connected (this would be device-specific)
            // For now, simulate the check
            val isHardwareConnected = checkThermalHardware()
            val isSimulated = !isHardwareConnected

            vm.updateThermalStatus(true, isSimulated, if (isHardwareConnected) "TC001" else null)
        } catch (e: Exception) {
            vm.updateThermalStatus(false, false)
        }
    }

    /**
     * Monitor GSR sensor status
     */
    private suspend fun monitorGsrSensorStatus() {
        try {
            // Check Shimmer connection status
            // This would integrate with the actual Shimmer sensor status
            val isConnected = checkShimmerConnection()

            vm.updateGsrStatus(isConnected, if (isConnected) "Shimmer3" else null)
        } catch (e: Exception) {
            vm.updateGsrStatus(false)
        }
    }

    /**
     * Check thermal hardware availability
     */
    private fun checkThermalHardware(): Boolean {
        // This would integrate with the actual TC001 SDK
        // For now, return false to simulate simulation mode
        return false
    }

    /**
     * Check Shimmer connection status
     */
    private fun checkShimmerConnection(): Boolean {
        // This would integrate with the actual Shimmer API
        // For now, return false to show disconnected state
        return false
    }

    /**
     * Show error dialog with proper material design
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                vm.clearError()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Update status text color based on content
     */
    private fun updateStatusTextColor(message: String) {
        statusText?.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    message.contains("error", ignoreCase = true) ||
                        message.contains("failed", ignoreCase = true) -> android.R.color.holo_red_dark
                    message.contains("recording", ignoreCase = true) -> android.R.color.holo_red_light
                    message.contains("ready", ignoreCase = true) ||
                        message.contains("connected", ignoreCase = true) ||
                        message.contains("success", ignoreCase = true) -> android.R.color.holo_green_dark
                    message.contains("checking", ignoreCase = true) ||
                        message.contains("starting", ignoreCase = true) ||
                        message.contains("stopping", ignoreCase = true) -> android.R.color.holo_blue_dark
                    else -> android.R.color.primary_text_light
                },
            ),
        )
    }

    /**
     * Initialize sensor status indicators with default states
     */
    private fun initializeSensorStatusIndicators() {
        // Initialize with default sensor names for display
        rgbSensorStatus?.updateStatus(
            "RGB",
            SensorStatusIndicator.SensorStatus(
                name = "RGB Camera",
                isActive = false,
                isHealthy = false,
                statusMessage = "Offline",
            ),
        )

        thermalSensorStatus?.updateStatus(
            "Thermal",
            SensorStatusIndicator.SensorStatus(
                name = "Thermal Camera",
                isActive = false,
                isHealthy = false,
                statusMessage = "Offline",
            ),
        )

        gsrSensorStatus?.updateStatus(
            "GSR",
            SensorStatusIndicator.SensorStatus(
                name = "GSR Sensor",
                isActive = false,
                isHealthy = false,
                statusMessage = "Disconnected",
            ),
        )

        pcSensorStatus?.updateStatus(
            "PC",
            SensorStatusIndicator.SensorStatus(
                name = "PC Link",
                isActive = false,
                isHealthy = false,
                statusMessage = "Not Connected",
            ),
        )

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
            statusMessage = this.statusMessage,
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
                Snackbar.LENGTH_LONG,
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
            // Enhanced UI feedback during permission checks
            vm.updateUiState { copy(statusText = "Checking permissions...") }
            btnStartRecording?.isEnabled = false

            if (permissionManager.areAllPermissionsGranted()) {
                startRecording()
            } else {
                vm.showToast("Checking permissions for all sensors")
                requestAllPermissions()
            }
        }

        btnStopRecording?.setOnClickListener {
            // Enhanced UI feedback during stop operation
            vm.updateUiState { copy(statusText = "Stopping recording...") }
            btnStopRecording?.isEnabled = false

            stopRecording()
        }
    }

    private fun startRecording() {
        lifecycleScope.launch {
            try {
                // Use ViewModel to start recording
                vm.startRecording()

                // Fallback to coordinator/controller if ViewModel doesn't handle it
                val coordinator = ensureMultiModalCoordinator()
                val sessionDir = File(applicationContext.filesDir, "sessions")
                if (!sessionDir.exists()) sessionDir.mkdirs()

                val startResult = coordinator.startRecording(sessionDir)

                if (startResult) {
                    showToast("Full multi-modal recording started")
                    vm.onRecordingCompleted("Full integration recording in progress")
                } else {
                    Log.w(TAG, "Coordinator failed, falling back to individual recorders")
                    ensureController().startSession()
                    showToast("Recording started (fallback mode)")
                    vm.onRecordingCompleted("Recording in progress (fallback)")
                }
            } catch (e: Exception) {
                vm.showError("Failed to start recording: ${e.message}")
                Log.e(TAG, "Recording start failed", e)
            }
        }
    }

    private fun stopRecording() {
        lifecycleScope.launch {
            try {
                // Use ViewModel to stop recording
                vm.stopRecording()

                // Fallback to coordinator/controller if ViewModel doesn't handle it
                val coordinator = multiModalCoordinator
                if (coordinator != null) {
                    val stopResult = coordinator.stopRecording()
                    if (stopResult) {
                        showToast("Full multi-modal recording stopped")
                        vm.onRecordingCompleted("Files saved to sessions directory")
                        return@launch
                    } else {
                        Log.w(TAG, "Coordinator stop failed, trying individual controller")
                    }
                }

                controller?.stopSession()
                showToast("Recording stopped")
                vm.onRecordingCompleted("Files saved successfully")
            } catch (e: Exception) {
                vm.showError("Failed to stop recording: ${e.message}")
                Log.e(TAG, "Recording stop failed", e)
                // Re-enable buttons on error
                btnStartRecording?.isEnabled = true
                btnStopRecording?.isEnabled = true
            }
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

    private fun requestAllPermissions() {
        vm.updateUiState { copy(statusText = "Requesting permissions...") }

        permissionManager.requestAllPermissions { allGranted ->
            if (allGranted) {
                vm.updateUiState {
                    copy(
                        statusText = "All permissions granted - Ready to record",
                        startButtonEnabled = true,
                    )
                }
                showToast("All sensor permissions granted. Ready to start recording!")
            } else {
                vm.updateUiState {
                    copy(statusText = "Some permissions denied - Limited functionality")
                }
                vm.showError("Some permissions were denied. Recording may not include all sensors.")
                Log.d(TAG, "Permission status: ${permissionManager.getPermissionStatus()}")
            }
        }
    }

    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showUserFriendlyError(message: String, context: String = "") {
        val fullMessage = if (context.isNotEmpty()) {
            "Error in $context: $message"
        } else {
            message
        }
        showToast(fullMessage)
        vm.showError(fullMessage)
    }

    private fun isRunningUnderTest(): Boolean {
        return try {
            Class.forName("androidx.test.espresso.Espresso")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
