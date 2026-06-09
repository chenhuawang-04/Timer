package com.timer.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class CloudSyncNetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager =
        requireNotNull(appContext.getSystemService(ConnectivityManager::class.java))

    fun canSync(wifiOnly: Boolean): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return if (wifiOnly) !connectivityManager.isActiveNetworkMetered else true
    }
}

