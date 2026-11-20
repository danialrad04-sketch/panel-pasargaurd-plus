package com.pasarguard.vpn.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.MainActivity
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.model.VpnSessionState
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.vpn.SingBoxConfigBuilder
import com.pasarguard.vpn.vpn.SingBoxEngineWrapper
import com.pasarguard.vpn.vpn.TunPlatformCallbacks
import com.pasarguard.vpn.vpn.VpnStateBus
import com.pasarguard.vpn.worker.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground Android [VpnService] managing the `sing-box` native TUN interface, per-app split
 * tunneling, live upload/download telemetry, and persistent notification with Disconnect action.
 */
@AndroidEntryPoint
class PasarGuardVpnService : VpnService() {

    @Inject lateinit var stateBus: VpnStateBus
    @Inject lateinit var parser: ProxyLinkParser
    @Inject lateinit var configBuilder: SingBoxConfigBuilder
    @Inject lateinit var engine: SingBoxEngineWrapper
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var notificationHelper: NotificationHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectInternal()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                val rawUri = intent.getStringExtra(EXTRA_RAW_URI).orEmpty()
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                val isAuto = intent.getBooleanExtra(EXTRA_AUTO_MODE, true)
                val parsed = parser.parseSingleUri(rawUri)?.copy(name = serverName.ifBlank { "VPN" })
                if (parsed == null) {
                    stateBus.update {
                        it.copy(
                            status = ConnectionStatus.ERROR,
                            errorMessage = "Invalid server configuration"
                        )
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                connectInternal(parsed, isAuto)
            }
        }
        return START_STICKY
    }

    private fun connectInternal(server: VpnServer, isAutoMode: Boolean) {
        notificationHelper.ensureChannels()
        startForegroundCompat(
            buildNotification(
                title = getString(R.string.notif_vpn_connecting_title, server.name),
                subtitle = BrandConfig.appName
            )
        )

        stateBus.update {
            VpnSessionState(
                status = ConnectionStatus.CONNECTING,
                activeServer = server,
                isAutoMode = isAutoMode
            )
        }

        serviceScope.launch {
            runCatching {
                val settings = settingsRepository.getSnapshot()
                val configJson = configBuilder.buildConfigJson(server)

                engine.start(
                    context = applicationContext,
                    configJson = configJson,
                    callbacks = object : TunPlatformCallbacks {
                        override fun protectSocket(fd: Int): Boolean = protect(fd)

                        override fun openTunInterface(): ParcelFileDescriptor? {
                            return buildTunInterface(
                                splitMode = settings.splitTunnelMode,
                                splitPackages = settings.splitTunnelPackages
                            )
                        }
                    }
                )

                val connectedAt = System.currentTimeMillis()
                stateBus.update {
                    it.copy(
                        status = ConnectionStatus.CONNECTED,
                        connectedSinceEpochMs = connectedAt,
                        errorMessage = null
                    )
                }

                updateNotification(
                    title = getString(R.string.notif_vpn_connected_title, server.name),
                    subtitle = getString(R.string.notif_vpn_speed, "0 B/s", "0 B/s")
                )

                engine.startTelemetry(serviceScope) { sample ->
                    stateBus.update { current ->
                        current.copy(
                            uploadBytesPerSec = sample.uploadBytesPerSec,
                            downloadBytesPerSec = sample.downloadBytesPerSec,
                            sessionUploadBytes = current.sessionUploadBytes + sample.uploadBytesPerSec,
                            sessionDownloadBytes = current.sessionDownloadBytes + sample.downloadBytesPerSec
                        )
                    }
                    updateNotification(
                        title = getString(R.string.notif_vpn_connected_title, server.name),
                        subtitle = getString(
                            R.string.notif_vpn_speed,
                            Formatters.formatSpeed(sample.downloadBytesPerSec),
                            Formatters.formatSpeed(sample.uploadBytesPerSec)
                        )
                    )
                }
            }.onFailure { err ->
                engine.stop()
                stateBus.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        errorMessage = err.localizedMessage ?: "Failed to start VPN tunnel",
                        uploadBytesPerSec = 0L,
                        downloadBytesPerSec = 0L,
                        connectedSinceEpochMs = null
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildTunInterface(
        splitMode: SplitTunnelMode,
        splitPackages: Set<String>
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(BrandConfig.appName)
            .setMtu(9000)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Always exclude our own VPN client package to prevent routing loops
        runCatching { builder.addDisallowedApplication(packageName) }

        when (splitMode) {
            SplitTunnelMode.DISABLED -> Unit
            SplitTunnelMode.BYPASS_SELECTED -> {
                splitPackages.forEach { pkg ->
                    if (pkg != packageName) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }

            SplitTunnelMode.ONLY_SELECTED -> {
                // In allowlist mode, only chosen packages enter the TUN
                if (splitPackages.isNotEmpty()) {
                    splitPackages.forEach { pkg ->
                        if (pkg != packageName) {
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    }
                }
            }
        }

        return builder.establish()
    }

    private fun disconnectInternal() {
        stateBus.update { it.copy(status = ConnectionStatus.DISCONNECTING) }
        engine.stop()
        stateBus.update {
            it.copy(
                status = ConnectionStatus.DISCONNECTED,
                uploadBytesPerSec = 0L,
                downloadBytesPerSec = 0L,
                sessionUploadBytes = 0L,
                sessionDownloadBytes = 0L,
                connectedSinceEpochMs = null,
                errorMessage = null
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIF_ID_VPN,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIF_ID_VPN, notification)
        }
    }

    private fun updateNotification(title: String, subtitle: String) {
        notificationHelper.notifyVpnStatus(buildNotification(title, subtitle))
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PasarGuardVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return notificationHelper.buildVpnNotification(
            title = title,
            subtitle = subtitle,
            contentIntent = openAppIntent,
            disconnectIntent = disconnectIntent
        )
    }

    override fun onRevoke() {
        disconnectInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        engine.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.pasarguard.vpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.pasarguard.vpn.action.DISCONNECT"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_RAW_URI = "extra_raw_uri"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_AUTO_MODE = "extra_auto_mode"
    }
}
