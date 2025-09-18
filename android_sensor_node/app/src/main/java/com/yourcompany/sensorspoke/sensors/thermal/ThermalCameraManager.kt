package com.yourcompany.sensorspoke.sensors.thermal

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.yourcompany.sensorspoke.utils.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ThermalCameraManager handles thermal camera lifecycle management and configuration.
 * Separates thermal camera management concerns from data recording logic.
 *
 * This class is responsible for:
 * - USB device discovery and permission management
 * - Thermal camera initialization and configuration
 * - Camera state monitoring
 * - Error handling and recovery
 */
class ThermalCameraManager(
    private val context: Context,
    private val permissionManager: PermissionManager? = null,
) {
    companion object {
        private const val TAG = "ThermalCameraManager"
        private const val TOPDON_VENDOR_ID = 0x4d54 // Topdon TC001 vendor ID
        private const val TC001_PRODUCT_ID_1 = 0x0100 // TC001 product ID variant 1
        private const val TC001_PRODUCT_ID_2 = 0x0200 // TC001 product ID variant 2
        private const val DEFAULT_FPS = 10 // Default 10 FPS for simulation
        private const val MAX_FPS = 25 // Maximum 25 Hz as per Topdon capability
    }

    // Camera state management
    private val _cameraState = MutableStateFlow(CameraState.UNINITIALIZED)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _cameraInfo = MutableStateFlow<ThermalCameraInfo?>(null)
    val cameraInfo: StateFlow<ThermalCameraInfo?> = _cameraInfo.asStateFlow()

    private val _frameRate = MutableStateFlow(DEFAULT_FPS.toDouble())
    val frameRate: StateFlow<Double> = _frameRate.asStateFlow()

    // Camera integration components
    private var realTopdonIntegration: RealTopdonIntegration? = null
    private var targetFps = DEFAULT_FPS

    /**
     * Camera states for thermal camera management
     */
    enum class CameraState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        RECORDING,
        ERROR,
    }

    /**
     * Thermal camera information container
     */
    data class ThermalCameraInfo(
        val deviceId: String,
        val deviceName: String,
        val isRealDevice: Boolean = false,
        val vendorId: Int = TOPDON_VENDOR_ID,
        val productId: Int = TC001_PRODUCT_ID_1,
        val maxFps: Int = MAX_FPS,
        val currentFps: Int = DEFAULT_FPS,
    )

    /**
     * Initialize the thermal camera system
     */
    suspend fun initialize(): Boolean {
        return try {
            Log.i(TAG, "Initializing thermal camera manager")
            _cameraState.value = CameraState.INITIALIZING

            initializeThermalCamera()

            _cameraState.value = CameraState.READY
            Log.i(TAG, "Thermal camera manager initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize thermal camera manager: ${e.message}", e)
            _cameraState.value = CameraState.ERROR
            false
        }
    }

    /**
     * Initialize thermal camera integration
     */
    private fun initializeThermalCamera() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList

            // Look for Topdon TC001 device
            val topdonDevice = deviceList.values.find { device ->
                isTopdonTC001Device(device)
            }

            if (topdonDevice != null) {
                Log.i(TAG, "Topdon TC001 device found: ${topdonDevice.deviceName}")

                // Request USB permissions if needed
                val hasPermission = checkUsbPermission(topdonDevice, usbManager)
                if (hasPermission) {
                    Log.i(TAG, "USB permission granted, initializing real Topdon integration")
                    initializeRealThermalIntegration()
                } else {
                    Log.w(TAG, "USB permission not granted, using simulation mode")
                    initializeSimulationIntegration()
                }
            } else {
                Log.w(TAG, "No Topdon TC001 device found - using simulation mode")
                initializeSimulationIntegration()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing thermal camera integration: ${e.message}", e)
            initializeSimulationIntegration()
        }
    }

    /**
     * Initialize real Topdon integration
     */
    private fun initializeRealThermalIntegration() {
        try {
            realTopdonIntegration = RealTopdonIntegration(context)
            
            // Use coroutine scope to call suspend function
            managerScope.launch {
                val success = realTopdonIntegration!!.initialize()
                
                if (success) {
                    Log.i(TAG, "Real Topdon TC001 integration initialized successfully")
                    updateCameraInfo(isReal = true)
                } else {
                    Log.w(TAG, "Real Topdon integration failed, falling back to simulation")
                    realTopdonIntegration = null
                    initializeSimulationIntegration()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing real Topdon integration: ${e.message}", e)
            realTopdonIntegration = null
            initializeSimulationIntegration()
        }
    }

    /**
     * Initialize simulation integration
     */
    private fun initializeSimulationIntegration() {
        topdonIntegration = TopdonThermalIntegration(context)

        if (topdonIntegration!!.initialize() == TopdonResult.SUCCESS) {
            configureDevice()
            Log.i(TAG, "Simulation thermal integration initialized")
            updateCameraInfo(isReal = false)
        } else {
            Log.w(TAG, "Failed to initialize simulation thermal integration")
            topdonIntegration = null
        }
    }

    /**
     * Update camera information
     */
    private fun updateCameraInfo(isReal: Boolean) {
        _cameraInfo.value = ThermalCameraInfo(
            deviceId = if (isReal) "TOPDON_TC001" else "THERMAL_SIM",
            deviceName = if (isReal) "Topdon TC001 Thermal Camera" else "Simulated Thermal Camera",
            isRealDevice = isReal,
            currentFps = targetFps,
        )
    }

    /**
     * Configure thermal camera frame rate (1-25 FPS)
     */
    fun setFrameRate(fps: Int): Boolean {
        return try {
            targetFps = fps.coerceIn(1, MAX_FPS)
            _frameRate.value = targetFps.toDouble()
            Log.i(TAG, "Thermal camera frame rate set to $targetFps FPS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set frame rate: ${e.message}", e)
            false
        }
    }

    /**
     * Configure device settings
     */
    private fun configureDevice() {
        topdonIntegration?.apply {
            setEmissivity(0.95f)
            setTemperatureRange(-20f, 150f)
            setThermalPalette(TopdonThermalPalette.IRON)
            enableAutoGainControl(true)
            enableTemperatureCompensation(true)
            configureDevice()
        }
    }

    /**
     * Check USB permission for Topdon device
     */
    private fun checkUsbPermission(device: UsbDevice, usbManager: UsbManager): Boolean {
        return if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already granted for ${device.deviceName}")
            true
        } else {
            Log.i(TAG, "USB permission required for Topdon TC001")
            // In a real implementation, request permission asynchronously
            false
        }
    }

    /**
     * Check if device is Topdon TC001
     */
    private fun isTopdonTC001Device(device: UsbDevice): Boolean {
        return device.vendorId == TOPDON_VENDOR_ID &&
            (device.productId == TC001_PRODUCT_ID_1 || device.productId == TC001_PRODUCT_ID_2)
    }

    /**
     * Get thermal integration for data capture
     */
    fun getThermalIntegration(): TopdonThermalIntegration? = topdonIntegration

    /**
     * Get real thermal integration for data capture
     */
    fun getRealThermalIntegration(): RealTopdonIntegration? = realTopdonIntegration

    /**
     * Check if camera is ready for recording
     */
    fun isReady(): Boolean = _cameraState.value == CameraState.READY

    /**
     * Check if using real thermal camera
     */
    fun isRealCamera(): Boolean = realTopdonIntegration != null

    /**
     * Update recording state
     */
    fun updateRecordingState(isRecording: Boolean) {
        _cameraState.value = if (isRecording) CameraState.RECORDING else CameraState.READY
    }

    /**
     * Get current frame rate
     */
    fun getCurrentFrameRate(): Int = targetFps

    /**
     * Clean up thermal camera resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up thermal camera manager")

        // Clean up real integration
        realTopdonIntegration?.let {
            it.stopStreaming()
            it.disconnect()
            realTopdonIntegration = null
        }

        // Clean up simulation integration
        topdonIntegration?.let {
            it.disconnect()
            it.cleanup()
            topdonIntegration = null
        }

        _cameraState.value = CameraState.UNINITIALIZED
        _cameraInfo.value = null
        _frameRate.value = DEFAULT_FPS.toDouble()
    }
}
