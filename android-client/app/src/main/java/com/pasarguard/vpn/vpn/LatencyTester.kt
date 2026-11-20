package com.pasarguard.vpn.vpn

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LatencyTester @Inject constructor() {

    /**
     * Measures TCP handshake RTT to [host]:[port].
     * Returns latency in milliseconds, or `-1` if unreachable/timed out.
     */
    suspend fun measureTcpPing(
        host: String,
        port: Int,
        timeoutMs: Int = 2_800
    ): Int = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
            }
        }.getOrDefault(-1)
    }
}
