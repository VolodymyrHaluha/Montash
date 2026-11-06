package com.frop.montash.data

import com.frop.montash.*

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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

/**
 * Prevents a single short Cloudflare/origin restart from making the UI flap
 * between connected and unavailable. Real network loss is still reported
 * immediately; only a reachable network with a temporarily failing server is
 * debounced.
 */
internal class ConnectionStateStabilizer(
    private val unavailableThreshold: Int = 3
) {
    init {
        require(unavailableThreshold > 0)
    }

    private var lastHealthy: ServerConnection? = null
    private var consecutiveUnavailable = 0

    fun update(observed: ServerConnection): ServerConnection = when (observed) {
        ServerConnection.LOCAL, ServerConnection.GLOBAL -> {
            lastHealthy = observed
            consecutiveUnavailable = 0
            observed
        }
        ServerConnection.SERVER_UNAVAILABLE -> {
            consecutiveUnavailable++
            if (consecutiveUnavailable >= unavailableThreshold) {
                ServerConnection.SERVER_UNAVAILABLE
            } else {
                lastHealthy ?: ServerConnection.CHECKING
            }
        }
        else -> {
            consecutiveUnavailable = 0
            lastHealthy = null
            observed
        }
    }
}

internal class ServerConnectionMonitor(private val context: Context, private val remote: RemoteProjects) {
    private val localSsid = BuildConfig.MONTASH_LOCAL_WIFI_SSID.trim()
    private val stabilizer = ConnectionStateStabilizer()

    suspend fun check(): ServerConnection = stabilizer.update(checkRaw())

    private suspend fun checkRaw(): ServerConnection = withContext(Dispatchers.IO) {
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
            return@withContext ServerConnection.LOCAL
        }
        if (remote.globalHealthy()) ServerConnection.GLOBAL
        else ServerConnection.SERVER_UNAVAILABLE
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
