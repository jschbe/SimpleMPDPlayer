package com.github.jschbe.simplempdplayer.mpd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/** Passive process-lifetime hints, including LAN-only Wi-Fi/VPNs. Never requests a network. */
internal class MpdNetworkObserver(context: Context, private val changed: (Boolean) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableSetOf<Network>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks.add(network)
            changed(true)
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            changed(networks.isNotEmpty())
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        try {
            // Callback and initial snapshot are serialized on Main. No synchronous queries in callbacks.
            manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback,
                Handler(Looper.getMainLooper()))
            networks.addAll(manager.allNetworks)
            changed(networks.isNotEmpty())
        } catch (_: SecurityException) {
            // Unknown availability must not disable LAN access; socket errors remain bounded.
            changed(true)
        }
    }
}
