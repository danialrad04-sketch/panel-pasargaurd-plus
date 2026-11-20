package com.pasarguard.vpn.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.domain.usecase.SelectBestServerUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var selectBestServer: SelectBestServerUseCase
    @Inject lateinit var vpnController: VpnController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.getSnapshot()
                if (settings.autoConnect && vpnController.hasVpnPermission()) {
                    val (server, isAuto) = selectBestServer(settings.selectedServerId)
                    if (server != null) {
                        vpnController.connect(server, isAuto)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
