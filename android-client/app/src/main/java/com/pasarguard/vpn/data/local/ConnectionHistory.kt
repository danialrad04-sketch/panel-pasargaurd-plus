package com.pasarguard.vpn.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pasarguard.vpn.domain.model.VpnServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionHistory @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("connection-history", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun networkType(): String {
        val manager = connectivity ?: return "offline"
        val current = manager.getNetworkCapabilities(manager.activeNetwork)
        val capabilities = if (current?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            manager.allNetworks.mapNotNull(manager::getNetworkCapabilities).firstOrNull {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } else current
        return when {
            capabilities == null -> "offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    suspend fun record(serverId: String, network: String, successful: Boolean) = withContext(Dispatchers.IO) {
        if (network != "wifi" && network != "cellular") return@withContext
        synchronized(this@ConnectionHistory) {
            val now = System.currentTimeMillis()
            val key = "$network:$serverId"
            val editor = preferences.edit()
            preferences.all.forEach { (storedKey, value) ->
                if (storedKey != key && recent(value as? String, now).isEmpty()) editor.remove(storedKey)
            }
            val entries = (recent(preferences.getString(key, null), now) + Sample(now, successful)).takeLast(12)
            editor.putString(key, JSONArray().apply {
                entries.forEach { put(JSONArray().put(it.time).put(it.success)) }
            }.toString()).apply()
        }
    }

    suspend fun rank(servers: List<VpnServer>): List<VpnServer> = withContext(Dispatchers.IO) {
        rank(servers, networkType())
    }

    internal fun rank(servers: List<VpnServer>, network: String): List<VpnServer> {
        val now = System.currentTimeMillis()
        val ranked = servers.mapIndexed { index, server ->
            val stats = recent(preferences.getString("$network:${server.id}", null), now)
            Ranked(server, index, if (stats.size >= 3) stats.count { it.success }.toDouble() / stats.size else null)
        }
        return ranked.sortedWith(
            compareByDescending<Ranked> { it.rate ?: -1.0 }
                .thenBy { it.server.pingMs?.takeIf { latency -> latency > 0 } ?: Int.MAX_VALUE }
                .thenComparator { first, second ->
                    if (first.rate == null && first.server.pingMs.isUnavailable() && second.server.pingMs.isUnavailable())
                        first.index.compareTo(second.index)
                    else first.server.id.compareTo(second.server.id)
                }
        ).map { it.server }
    }

    private fun Int?.isUnavailable() = this == null || this <= 0

    private fun recent(data: String?, now: Long): List<Sample> {
        if (data == null) return emptyList()
        return runCatching {
            val json = JSONArray(data)
            (0 until json.length()).mapNotNull { index ->
                val entry = json.getJSONArray(index)
                val time = entry.getLong(0)
                if (now - time in 0..2_592_000_000L) Sample(time, entry.getBoolean(1)) else null
            }
        }.getOrDefault(emptyList())
    }

    private data class Sample(val time: Long, val success: Boolean)
    private data class Ranked(val server: VpnServer, val index: Int, val rate: Double?)
}
