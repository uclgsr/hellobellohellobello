package com.yourcompany.sensorspoke.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Dashboard Fragment - Main overview of system status
 */
class DashboardFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = TextView(requireContext())
        view.text = "Dashboard\n\nSystem Status: Ready\nSensors: 4 Available\nConnection: PC Hub Connected"
        view.setPadding(32, 32, 32, 32)
        return view
    }
}
