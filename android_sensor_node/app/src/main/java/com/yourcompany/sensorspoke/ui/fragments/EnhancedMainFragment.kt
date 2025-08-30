package com.yourcompany.sensorspoke.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yourcompany.sensorspoke.R
import com.yourcompany.sensorspoke.sensors.thermal.tc001.TC001ConnectType
import com.yourcompany.sensorspoke.sensors.thermal.tc001.TC001UIController
import com.yourcompany.sensorspoke.ui.MainActivity
import com.yourcompany.sensorspoke.ui.popup.DelPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enhanced MainFragment with IRCamera-style device management
 *
 * Integrates comprehensive device discovery and management UI
 * based on IRCamera's MainFragment implementation
 */
@SuppressLint("NotifyDataSetChanged")
class EnhancedMainFragment :
    Fragment(),
    View.OnClickListener {
    private lateinit var uiController: TC001UIController
    private lateinit var adapter: DeviceAdapter

    // UI components
    private lateinit var clHasDevice: View
    private lateinit var clNoDevice: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvConnectDevice: TextView
    private lateinit var ivAdd: ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
        initData()
    }

    private fun initView(view: View) {
        // Initialize UI components
        clHasDevice = view.findViewById(R.id.cl_has_device)
        clNoDevice = view.findViewById(R.id.cl_no_device)
        recyclerView = view.findViewById(R.id.recycler_view)
        tvConnectDevice = view.findViewById(R.id.tv_connect_device)
        ivAdd = view.findViewById(R.id.iv_add)

        // Initialize UI controller
        uiController = ViewModelProvider(this)[TC001UIController::class.java]

        // Setup adapter with IRCamera-style callbacks
        adapter = DeviceAdapter()
        adapter.hasConnectLine = false // Will be updated via LiveData
        adapter.onItemClickListener = { type ->
            uiController.handleDeviceClick(type)
            handleDeviceConnection(type)
        }
        adapter.onItemLongClickListener = { anchorView, type ->
            showDeletePopup(anchorView, type)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Setup click listeners
        tvConnectDevice.setOnClickListener(this)
        ivAdd.setOnClickListener(this)

        // Observe UI controller state
        observeUIController()

        // Lifecycle management
        viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    uiController.refresh()
                }
            },
        )
    }

    private fun initData() {
        // Initialize data and start monitoring
        uiController.refresh()
    }

    private fun observeUIController() {
        uiController.hasConnectLine.observe(viewLifecycleOwner) { hasDevice ->
            adapter.hasConnectLine = hasDevice
            refreshDeviceVisibility()
        }

        uiController.deviceConnectionStatus.observe(viewLifecycleOwner) { status ->
            adapter.notifyDataSetChanged()
        }
    }

    private fun refreshDeviceVisibility() {
        val hasAnyDevice = adapter.hasConnectLine
        clHasDevice.isVisible = hasAnyDevice
        clNoDevice.isVisible = !hasAnyDevice
    }

    private fun handleDeviceConnection(type: TC001ConnectType) {
        when (type) {
            TC001ConnectType.LINE -> {
                // Navigate to thermal camera interface and initiate TC001 connection
                CoroutineScope(Dispatchers.Main).launch {
                    // Start thermal preview for TC001
                    (activity as? MainActivity)?.navigateToThermalPreview()

                    // Update UI to show connection attempt
                    uiController.updateConnectionStatus(true)
                    Log.i("EnhancedMainFragment", "Navigating to thermal preview and updating connection status")
                }
            }
            TC001ConnectType.WIFI -> {
                // Handle WiFi connection
            }
            TC001ConnectType.BLE -> {
                // Handle BLE connection
            }
        }
    }

    private fun showDeletePopup(
        anchorView: View,
        type: TC001ConnectType,
    ) {
        val popup = DelPopup(requireContext())
        popup.onDelListener = {
            // Confirm deletion with dialog
            showDeleteConfirmationDialog(type)
        }
        popup.show(anchorView)
    }

    private fun showDeleteConfirmationDialog(type: TC001ConnectType) {
        // Simple confirmation for now
        CoroutineScope(Dispatchers.Main).launch {
            // Remove device based on type
            when (type) {
                TC001ConnectType.LINE -> {
                    uiController.updateConnectionStatus(false)
                }
                else -> {
                    // Handle other device types
                }
            }
            uiController.refresh()
        }
    }

    override fun onClick(v: View?) {
        when (v) {
            tvConnectDevice, ivAdd -> {
                // Navigate to device connection screen
                // For now, simulate device addition
                uiController.updateConnectionStatus(true)
            }
        }
    }

    /**
     * Initialize TC001 connection when device is selected
     * (Now simplified since ThermalPreviewFragment handles full integration)
     */
    private fun initializeTC001Connection() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("EnhancedMainFragment", "TC001 connection handling delegated to ThermalPreviewFragment")
                withContext(Dispatchers.Main) {
                    uiController.updateConnectionStatus(true)
                }
            } catch (e: Exception) {
                Log.e("EnhancedMainFragment", "Error during TC001 connection delegation", e)
                withContext(Dispatchers.Main) {
                    uiController.updateConnectionStatus(false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiController.refresh()
        adapter.notifyDataSetChanged()
    }

    /**
     * Device adapter for TC001 devices with IRCamera styling
     */
    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        var hasConnectLine: Boolean = false
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        var onItemClickListener: ((type: TC001ConnectType) -> Unit)? = null
        var onItemLongClickListener: ((view: View, type: TC001ConnectType) -> Unit)? = null

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder =
            ViewHolder(
                LayoutInflater
                    .from(parent.context)
                    .inflate(R.layout.item_device_connect, parent, false),
            )

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val type = getConnectType(position)
            val hasConnect =
                when (type) {
                    TC001ConnectType.LINE -> hasConnectLine
                    else -> false
                }

            // Setup device display based on IRCamera logic
            holder.itemView.findViewById<TextView>(R.id.tv_title)?.apply {
                isVisible = position == 0
                text = "Wired Connection"
            }

            holder.itemView.findViewById<View>(R.id.iv_bg)?.isSelected = hasConnect
            holder.itemView.findViewById<TextView>(R.id.tv_device_name)?.apply {
                isSelected = hasConnect
                text = "TC001 Thermal Camera"
            }
            holder.itemView.findViewById<View>(R.id.view_device_state)?.isSelected = hasConnect
            holder.itemView.findViewById<TextView>(R.id.tv_device_state)?.apply {
                isSelected = hasConnect
                text = if (hasConnect) "online" else "offline"
            }

            // Hide battery info for TC001 (no battery)
            holder.itemView.findViewById<TextView>(R.id.tv_battery)?.isVisible = false
            holder.itemView.findViewById<View>(R.id.battery_view)?.isVisible = false
        }

        override fun getItemCount(): Int = if (hasConnectLine) 1 else 0

        private fun getConnectType(position: Int): TC001ConnectType = TC001ConnectType.LINE

        inner class ViewHolder(
            itemView: View,
        ) : RecyclerView.ViewHolder(itemView) {
            init {
                itemView.findViewById<View>(R.id.iv_bg)?.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClickListener?.invoke(getConnectType(position))
                    }
                }

                itemView.findViewById<View>(R.id.iv_bg)?.setOnLongClickListener { view ->
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val deviceType = getConnectType(position)
                        // Only allow deletion when device is offline
                        if (!hasConnectLine) {
                            onItemLongClickListener?.invoke(view, deviceType)
                        }
                    }
                    true
                }
            }
        }
    }
}
