package com.frop.montash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ServerConnection(val label: String) {
    CHECKING("Перевірка підключення…"),
    LOCAL("Підключено до LOCAL"),
    GLOBAL("Підключено до GLOBAL"),
    OFFLINE("Немає підключення"),
    SERVER_UNAVAILABLE("Сервер недоступний"),
    NOT_CONFIGURED("Сервер не налаштовано")
}

internal class ServerConnectionMonitor(private val context: Context, private val remote: RemoteProjects) {
    private val localSsid = BuildConfig.MONTASH_LOCAL_WIFI_SSID.trim()
    private var lastGlobalSuccessAt = 0L

    suspend fun check(): ServerConnection = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return@withContext ServerConnection.OFFLINE
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return@withContext ServerConnection.OFFLINE
        val connected = listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN
        ).any(capabilities::hasTransport)
        if (!connected) return@withContext ServerConnection.OFFLINE
        if (!remote.enabled) return@withContext ServerConnection.NOT_CONFIGURED

        val onLocalWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            localSsid.isNotEmpty() && currentSsid(capabilities) == localSsid
        if (onLocalWifi && remote.localHealthy()) {
            lastGlobalSuccessAt = 0L
            return@withContext ServerConnection.LOCAL
        }
        val result = if (remote.globalHealthy()) {
            lastGlobalSuccessAt = SystemClock.elapsedRealtime()
            ServerConnection.GLOBAL
        } else if (lastGlobalSuccessAt != 0L && SystemClock.elapsedRealtime() - lastGlobalSuccessAt < 15_000) {
            ServerConnection.GLOBAL
        } else {
            ServerConnection.SERVER_UNAVAILABLE
        }
        result
    }

    private fun currentSsid(capabilities: NetworkCapabilities): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            capabilities.transportInfo as? WifiInfo
        } else null
        val current = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        @Suppress("DEPRECATION")
        val raw = current ?: context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        return raw?.trim('"')?.takeUnless { it == WifiManager.UNKNOWN_SSID }
    }
}
