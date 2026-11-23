package com.pasarguard.vpn.vpn.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.pasarguard.vpn.MainActivity
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.domain.usecase.SelectBestServerUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android Quick Settings Tile (API 24+) to toggle VPN directly from the notification shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class VpnQuickSettingsTileService : TileService() {

    @Inject lateinit var vpnController: VpnController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var selectBestServer: SelectBestServerUseCase

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val session = vpnController.sessionState.value
        when (session.status) {
            ConnectionStatus.CONNECTED,
            ConnectionStatus.CONNECTING -> {
                tileScope.launch {
                    vpnController.disconnect()
                    refreshTile()
                }
            }

            else -> {
                if (VpnService.prepare(this) != null) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(MainActivity.EXTRA_TOGGLE_VPN, true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pi = android.app.PendingIntent.getActivity(
                            this,
                            201,
                            intent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                    return
                }

                tileScope.launch {
                    val settings = settingsRepository.getSnapshot()
                    val (server, isAuto) = selectBestServer(settings.selectedServerId)
                    if (server != null) {
                        vpnController.connect(server, isAuto)
                    } else {
                        val intent = Intent(this@VpnQuickSettingsTileService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pi = android.app.PendingIntent.getActivity(
                                this@VpnQuickSettingsTileService,
                                202,
                                intent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            startActivityAndCollapse(pi)
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(intent)
                        }
                    }
                    refreshTile()
                }
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val session = vpnController.sessionState.value
        tile.label = getString(R.string.app_name)
        when (session.status) {
            ConnectionStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = session.activeServer?.name ?: getString(R.string.status_connected)
                }
            }

            ConnectionStatus.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_connecting)
                }
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.status_disconnected)
                }
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestListeningStateCompat(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    requestListeningState(
                        context,
                        ComponentName(context, VpnQuickSettingsTileService::class.java)
                    )
                }
            }
        }
    }
}
