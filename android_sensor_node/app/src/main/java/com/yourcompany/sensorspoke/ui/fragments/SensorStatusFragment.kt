package com.yourcompany.sensorspoke.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.yourcompany.sensorspoke.R

/**
 * Sensor Status Fragment
 */
class SensorStatusFragment : Fragment() {
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = TextView(requireContext())
        view.text = "Sensor Status\n\nRGB Camera: Ready\nThermal Camera: Ready\nGSR Sensor: Ready\nAudio: Ready"
        view.setPadding(32, 32, 32, 32)
        return view
    }
}