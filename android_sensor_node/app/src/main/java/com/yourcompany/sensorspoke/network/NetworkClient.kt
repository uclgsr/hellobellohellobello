package com.yourcompany.sensorspoke.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * NetworkClient encapsulates Android NSD (Zeroconf) service registration
 * for the Sensor Spoke. Phase 1 only registers a TCP service for the PC Hub.
 */
class NetworkClient(private val context: Context) {

    private var nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(serviceType: String, serviceName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceType = if (serviceType.endsWith(".local.")) serviceType else "$serviceType.local."
            this.serviceName = serviceName
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                // Service registered
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Log or handle registration failure
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (_: Exception) { }
        }
        registrationListener = null
    }
}
