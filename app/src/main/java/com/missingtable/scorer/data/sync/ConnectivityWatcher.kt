package com.missingtable.scorer.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tracks default-network availability and kicks the sync engine on regain. */
class ConnectivityWatcher(context: Context, private val onRegained: () -> Unit) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(isCurrentlyOnline())
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
            onRegained()
        }

        override fun onLost(network: Network) {
            _online.value = isCurrentlyOnline()
        }
    }

    fun start() {
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun isCurrentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
