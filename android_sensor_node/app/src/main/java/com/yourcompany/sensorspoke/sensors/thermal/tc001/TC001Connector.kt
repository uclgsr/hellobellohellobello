package com.yourcompany.sensorspoke.sensors.thermal.tc001

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

/**
 * TC001Connector - Core thermal camera connection handler
 * 
 * Manages connection to Topdon TC001 thermal camera via USB with
 * enhanced device management capabilities from IRCamera
 */
class TC001Connector(private val context: Context) {
    companion object {
        private const val TAG = "TC001Connector"
        private const val TC001_VENDOR_ID = 0x4d54  // Topdon vendor ID
        private const val TC001_PRODUCT_ID = 0x0100 // TC001 product ID
    }

    private val _connectionState = MutableLiveData<TC001ConnectionState>()
    val connectionState: LiveData<TC001ConnectionState> = _connectionState

    private val _deviceInfo = MutableLiveData<TC001DeviceInfo?>()
    val deviceInfo: LiveData<TC001DeviceInfo?> = _deviceInfo

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isConnected = false
    private var currentDevice: UsbDevice? = null

    init {
        _connectionState.value = TC001ConnectionState.DISCONNECTED
        initUSBMonitoring()
    }

    /**
     * Initialize USB monitoring for TC001 devices
     */
    private fun initUSBMonitoring() {
        scope.launch {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                scanForTC001Devices(usbManager)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize USB monitoring", e)
                _connectionState.value = TC001ConnectionState.ERROR
            }
        }
    }

    /**
     * Scan for connected TC001 devices
     */
    private suspend fun scanForTC001Devices(usbManager: UsbManager) = withContext(Dispatchers.IO) {
        try {
            val deviceList = usbManager.deviceList
            for (device in deviceList.values) {
                if (isTC001Device(device)) {
                    Log.i(TAG, "TC001 device found: ${device.deviceName}")
                    withContext(Dispatchers.Main) {
                        handleDeviceFound(device)
                    }
                    return@withContext
                }
            }
            
            withContext(Dispatchers.Main) {
                _connectionState.value = TC001ConnectionState.NOT_FOUND
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for TC001 devices", e)
            withContext(Dispatchers.Main) {
                _connectionState.value = TC001ConnectionState.ERROR
            }
        }
    }

    /**
     * Check if USB device is a TC001 thermal camera
     */
    private fun isTC001Device(device: UsbDevice): Boolean {
        return device.vendorId == TC001_VENDOR_ID && device.productId == TC001_PRODUCT_ID
    }

    /**
     * Handle TC001 device discovery
     */
    private fun handleDeviceFound(device: UsbDevice) {
        currentDevice = device
        _connectionState.value = TC001ConnectionState.FOUND
        
        val deviceInfo = TC001DeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            serialNumber = device.serialNumber ?: "Unknown"
        )
        _deviceInfo.value = deviceInfo
        
        Log.i(TAG, "TC001 device info: $deviceInfo")
    }

    /**
     * Attempt to connect to the TC001 device
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            _connectionState.postValue(TC001ConnectionState.CONNECTING)
            
            currentDevice?.let { device ->
                // Simulate connection process
                delay(1000)
                
                isConnected = true
                _connectionState.postValue(TC001ConnectionState.CONNECTED)
                
                Log.i(TAG, "Successfully connected to TC001 device")
                return@withContext true
            }
            
            _connectionState.postValue(TC001ConnectionState.ERROR)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to TC001", e)
            _connectionState.postValue(TC001ConnectionState.ERROR)
            return@withContext false
        }
    }

    /**
     * Disconnect from TC001 device
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            _connectionState.postValue(TC001ConnectionState.DISCONNECTING)
            
            // Simulate disconnection process
            delay(500)
            
            isConnected = false
            currentDevice = null
            _connectionState.postValue(TC001ConnectionState.DISCONNECTED)
            _deviceInfo.postValue(null)
            
            Log.i(TAG, "Disconnected from TC001 device")
        } catch (e: Exception) {
            Log.e(TAG, "Error during TC001 disconnection", e)
            _connectionState.postValue(TC001ConnectionState.ERROR)
        }
    }

    /**
     * Check if TC001 is currently connected
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Refresh device scan
     */
    suspend fun refreshDeviceScan() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        scanForTC001Devices(usbManager)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * TC001 connection states
 */
enum class TC001ConnectionState {
    DISCONNECTED,
    NOT_FOUND,
    FOUND,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

/**
 * TC001 device information
 */
data class TC001DeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String
)