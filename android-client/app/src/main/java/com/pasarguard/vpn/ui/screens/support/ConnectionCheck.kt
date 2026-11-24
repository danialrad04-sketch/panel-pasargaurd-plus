package com.pasarguard.vpn.ui.screens.support

import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.data.local.ConnectionHistory
import com.pasarguard.vpn.data.repository.SupportDiagnostic
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.repository.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject

class ConnectionCheck @Inject constructor(
    private val history: ConnectionHistory,
    private val servers: ServerRepository,
    private val subscriptions: SubscriptionRepository,
    private val controller: VpnController
) {
    suspend fun run(): SupportDiagnostic = withContext(Dispatchers.IO) {
        val network = history.networkType()
        if (network == "offline") return@withContext SupportDiagnostic(network, "network")
        val host = BuildConfig.DEFAULT_BASE_URL.toHttpUrlOrNull()?.host
        if (host != null && host != "panel.example.com") {
            try {
                InetAddress.getAllByName(host)
            } catch (_: UnknownHostException) {
                return@withContext SupportDiagnostic(network, "dns")
            }
        }
        val subscription = subscriptions.observeSubscription().first()
        if (subscription?.status in setOf(SubscriptionStatus.EXPIRED, SubscriptionStatus.LIMITED, SubscriptionStatus.DISABLED)) {
            return@withContext SupportDiagnostic(network, "subscription")
        }
        val server = controller.sessionState.value.activeServer
        if (server != null && servers.pingServer(server) <= 0) {
            return@withContext SupportDiagnostic(network, "server")
        }
        SupportDiagnostic(network, "unknown")
    }
}
