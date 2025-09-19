package com.yourcompany.sensorspoke.ui.fragments

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.sensors.rgb.RgbCameraRecorder
import com.yourcompany.sensorspoke.utils.PreviewBus
import kotlinx.coroutines.launch

/**
 * Fragment for displaying RGB camera preview with RAW DNG support
 */
class RgbPreviewFragment : Fragment() {
    private var previewImageView: ImageView? = null
    private var statusText: TextView? = null
    private var resolutionText: TextView? = null
    private var framerateText: TextView? = null
    private var deviceModelText: TextView? = null
    private var rawDngControls: LinearLayout? = null
    private var rawDngToggle: Switch? = null
    private var rawDngStatusText: TextView? = null

    private var frameCount = 0
    private var lastFrameTime = 0L
    
    // Camera recorder instance (would be injected in real implementation)
    private var rgbCameraRecorder: RgbCameraRecorder? = null

    private val previewListener: (ByteArray, Long) -> Unit = { bytes, timestampNs ->
        activity?.runOnUiThread {
            updatePreview(bytes, timestampNs)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_rgb_preview, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        previewImageView = view.findViewById(R.id.previewImageView)
        statusText = view.findViewById(R.id.statusText)
        resolutionText = view.findViewById(R.id.resolutionText)
        framerateText = view.findViewById(R.id.framerateText)
        deviceModelText = view.findViewById(R.id.deviceModelText)
        rawDngControls = view.findViewById(R.id.rawDngControls)
        rawDngToggle = view.findViewById(R.id.rawDngToggle)
        rawDngStatusText = view.findViewById(R.id.rawDngStatusText)

        setupRawDngControls()
    }
    
    /**
     * Setup RAW DNG controls and Samsung device detection
     */
    private fun setupRawDngControls() {
        // This would typically be injected via dependency injection
        // For now, we'll check if the device supports RAW DNG
        lifecycleScope.launch {
            checkRawDngSupport()
        }
        
        rawDngToggle?.setOnCheckedChangeListener { _, isChecked ->
            handleRawDngToggle(isChecked)
        }
    }
    
    /**
     * Check if device supports RAW DNG and update UI accordingly
     */
    private suspend fun checkRawDngSupport() {
        try {
            // In a real implementation, this would come from a ViewModel or Service
            // For now, detect Samsung devices directly
            val isSamsungDevice = android.os.Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
            val deviceModel = android.os.Build.MODEL.uppercase()
            
            // Samsung devices known to support Camera2 Level 3
            val samsungLevel3Models = listOf(
                "SM-S901", "SM-S906", "SM-S908", // Galaxy S22 series
                "SM-S911", "SM-S916", "SM-S918", // Galaxy S23 series  
                "SM-S921", "SM-S926", "SM-S928", // Galaxy S24 series
                "SM-G991", "SM-G996", "SM-G998"  // Galaxy S21 series
            )
            
            val isSamsungLevel3 = isSamsungDevice && samsungLevel3Models.any { model ->
                deviceModel.startsWith(model)
            }
            
            activity?.runOnUiThread {
                if (isSamsungLevel3) {
                    rawDngControls?.visibility = View.VISIBLE
                    rawDngStatusText?.text = "Samsung Level 3 supported"
                    rawDngStatusText?.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
                } else if (isSamsungDevice) {
                    rawDngControls?.visibility = View.VISIBLE
                    rawDngStatusText?.text = "Samsung device (Level 3 not supported)"
                    rawDngStatusText?.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light))
                    rawDngToggle?.isEnabled = false
                } else {
                    rawDngControls?.visibility = View.GONE
                }
                
                // Update device model display
                deviceModelText?.text = "Device: ${android.os.Build.MODEL}"
            }
        } catch (e: Exception) {
            // Handle error silently in UI context
            rawDngControls?.visibility = View.GONE
        }
    }
    
    /**
     * Handle RAW DNG toggle state change
     */
    private fun handleRawDngToggle(enabled: Boolean) {
        try {
            // This would typically interact with the recording system
            // For now, just show status feedback
            val statusMessage = if (enabled) "RAW DNG mode enabled" else "Standard mode enabled"
            statusText?.text = statusMessage
            
        } catch (e: Exception) {
            // Reset toggle on error
            rawDngToggle?.isChecked = false
            statusText?.text = "Error: ${e.message}"
        }
    }
    
    /**
     * Set the RGB camera recorder instance (for dependency injection)
     */
    fun setRgbCameraRecorder(recorder: RgbCameraRecorder) {
        this.rgbCameraRecorder = recorder
        // Refresh RAW DNG support check
        lifecycleScope.launch {
            checkRawDngSupport()
        }
    }

    override fun onResume() {
        super.onResume()
        PreviewBus.subscribe(previewListener)
        statusText?.text = "Waiting for preview..."
    }

    override fun onPause() {
        super.onPause()
        PreviewBus.unsubscribe(previewListener)
    }

    private fun updatePreview(
        jpegBytes: ByteArray,
        timestampNs: Long,
    ) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap != null) {
                previewImageView?.setImageBitmap(bitmap)
            statusText?.text = "Live preview active"
            
            // Show RAW DNG status if toggle is enabled
            rawDngToggle?.let { toggle ->
                if (toggle.isChecked) {
                    statusText?.text = "Live preview active + RAW DNG"
                }
            }
                resolutionText?.text = "Resolution: ${bitmap.width}x${bitmap.height}"

                // Calculate frame rate
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (lastFrameTime > 0) {
                    val deltaMs = currentTime - lastFrameTime
                    if (deltaMs > 1000) { // Update every second
                        val fps = (frameCount * 1000.0 / deltaMs).toInt()
                        framerateText?.text = "Frame Rate: $fps fps"
                        frameCount = 0
                        lastFrameTime = currentTime
                    }
                } else {
                    lastFrameTime = currentTime
                }
            }
        } catch (e: Exception) {
            statusText?.text = "Preview error: ${e.message}"
        }
    }

    companion object {
        fun newInstance(): RgbPreviewFragment = RgbPreviewFragment()
    }
}
