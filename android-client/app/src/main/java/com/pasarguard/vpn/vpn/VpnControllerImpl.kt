package com.pasarguard.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.model.VpnSessionState
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.vpn.service.PasarGuardVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateBus: VpnStateBus
) : VpnController {

    override val sessionState: StateFlow<VpnSessionState> = stateBus.state

    override fun hasVpnPermission(): Boolean = VpnService.prepare(context) == null

    override suspend fun connect(server: VpnServer, isAutoMode: Boolean) {
        val intent = Intent(context, PasarGuardVpnService::class.java).apply {
            action = PasarGuardVpnService.ACTION_CONNECT
            putExtra(PasarGuardVpnService.EXTRA_SERVER_ID, server.id)
            putExtra(PasarGuardVpnService.EXTRA_RAW_URI, server.rawUri)
            putExtra(PasarGuardVpnService.EXTRA_SERVER_NAME, server.name)
            putExtra(PasarGuardVpnService.EXTRA_AUTO_MODE, isAutoMode)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override suspend fun disconnect() {
        val intent = Intent(context, PasarGuardVpnService::class.java).apply {
            action = PasarGuardVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }
}
