package com.pasarguard.vpn.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.MainActivity
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.model.DnsPreset
import com.pasarguard.vpn.domain.model.SessionStatRecord
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.model.VpnEngineType
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.model.VpnSessionState
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.util.SanitizedLogBuffer
import com.pasarguard.vpn.vpn.TunPlatformCallbacks
import com.pasarguard.vpn.vpn.VpnStateBus
import com.pasarguard.vpn.vpn.core.CoreSelectionResult
import com.pasarguard.vpn.vpn.core.CoreSelector
import com.pasarguard.vpn.vpn.core.SingBoxCore
import com.pasarguard.vpn.vpn.core.VpnBuildOptions
import com.pasarguard.vpn.vpn.core.VpnCore
import com.pasarguard.vpn.vpn.core.XrayCore
import com.pasarguard.vpn.widget.PasarGuardWidgetProvider
import com.pasarguard.vpn.worker.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import com.pasarguard.vpn.data.local.ConnectionHistory
import com.pasarguard.vpn.domain.model.AutoSelectMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Multi-core foreground Android [VpnService]:
 * - Primary engine: [XrayCore] (`libv2ray.aar` / Xray-core v26 native Android TUN)
 * - Secondary engine: [SingBoxCore] (`libbox.aar`, used for Hysteria2/TUIC/WireGuard or user override)
 * - Supports full Stage 2A options: Routing modes, DNS presets, Fragment, Mux, Sniffing, MTU, IPv6,
 *   Split Tunneling, Auto-reconnect (exponential backoff), Auto-failover, Disguise notification,
 *   Session statistics recording, Home-Screen Widget & Quick Settings Tile synchronization.
 */
@AndroidEntryPoint
class PasarGuardVpnService : VpnService() {

    @Inject lateinit var stateBus: VpnStateBus
    @Inject lateinit var parser: ProxyLinkParser
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var coreSelector: CoreSelector
    @Inject lateinit var xrayCore: XrayCore
    @Inject lateinit var singBoxCore: SingBoxCore
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var connectionHistory: ConnectionHistory

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeCore: VpnCore? = null

    @Volatile
    private var activeTunPfd: ParcelFileDescriptor? = null

    @Volatile
    private var lastServer: VpnServer? = null

    @Volatile
    private var lastAutoMode: Boolean = true

    @Volatile
    private var disguiseMode: Boolean = false

    @Volatile
    private var manualDisconnectRequested: Boolean = false

    private var connectJob: Job? = null
    private var statsCollectorJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                manualDisconnectRequested = true
                disconnectInternal()
                return START_NOT_STICKY
            }

            ACTION_CONNECT -> {
                manualDisconnectRequested = false
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
                val rawUri = intent.getStringExtra(EXTRA_RAW_URI).orEmpty()
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                val isAuto = intent.getBooleanExtra(EXTRA_AUTO_MODE, true)

                notificationHelper.ensureChannels()
                startForegroundCompat(
                    buildNotification(
                        title = getString(R.string.notif_vpn_connecting_title, serverName.ifBlank { "VPN" }),
                        subtitle = BrandConfig.appName
                    )
                )

                connectJob?.cancel()
                reconnectJob?.cancel()
                connectJob = serviceScope.launch {
                    val resolvedServer = resolveServer(serverId, rawUri, serverName)
                    if (resolvedServer == null) {
                        failConnection("Invalid server configuration")
                        return@launch
                    }
                    connectInternal(resolvedServer, isAuto, allowFailover = true)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun resolveServer(
        serverId: String,
        rawUri: String,
        fallbackName: String
    ): VpnServer? {
        if (serverId.isNotBlank()) {
            serverRepository.getServerById(serverId)?.let { return it }
        }
        if (rawUri.isNotBlank()) {
            parser.parseSingleUri(rawUri)?.let { parsed ->
                return parsed.copy(name = fallbackName.ifBlank { parsed.name })
            }
        }
        return null
    }

    private suspend fun connectInternal(
        server: VpnServer,
        isAutoMode: Boolean,
        allowFailover: Boolean
    ) {
        val settings = settingsRepository.getSnapshot()
        disguiseMode = settings.disguiseNotification
        lastServer = server
        lastAutoMode = isAutoMode

        val selectedEngine = when (val selection = coreSelector.select(server, settings.corePreference)) {
            is CoreSelectionResult.Selected -> selection.engine
            is CoreSelectionResult.Incompatible -> {
                SanitizedLogBuffer.warn(TAG, "Core incompatible: ${selection.reason}")
                failConnection(selection.reason, server)
                return
            }
        }

        val targetCore: VpnCore = when (selectedEngine) {
            VpnEngineType.XRAY -> xrayCore
            VpnEngineType.SING_BOX -> singBoxCore
        }

        SanitizedLogBuffer.info(
            TAG,
            "Connecting to ${server.name} via ${selectedEngine.label} (route=${settings.routingMode}, dns=${settings.dnsPreset})"
        )

        stateBus.update {
            VpnSessionState(
                status = ConnectionStatus.CONNECTING,
                activeServer = server,
                activeEngine = selectedEngine,
                isAutoMode = isAutoMode
            )
        }
        syncExternalSurfaces(ConnectionStatus.CONNECTING, server.name, selectedEngine.label)

        updateNotification(
            title = getString(R.string.notif_vpn_connecting_title, server.name),
            subtitle = selectedEngine.label
        )

        val networkType = connectionHistory.networkType()
        runCatching {
            stopActiveCoreAndTun()

            val options = VpnBuildOptions.fromSettings(settings)
            val callbacks = object : TunPlatformCallbacks {
                override fun protectSocket(fd: Int): Boolean = protect(fd)

                override fun openTunInterface(): ParcelFileDescriptor? {
                    val pfd = buildTunInterface(
                        engine = selectedEngine,
                        settings = settings
                    )
                    activeTunPfd = pfd
                    return pfd
                }
            }

            val tunFd = if (selectedEngine == VpnEngineType.XRAY) {
                val pfd = callbacks.openTunInterface()
                    ?: throw IllegalStateException("Android VPN permission was not granted or TUN could not be established")
                pfd.fd
            } else {
                -1
            }

            targetCore.start(
                server = server,
                tunFd = tunFd,
                options = options,
                callbacks = callbacks
            )
            activeCore = targetCore

            val connectedAt = System.currentTimeMillis()
            stateBus.update {
                it.copy(
                    status = ConnectionStatus.CONNECTED,
                    activeServer = server,
                    activeEngine = selectedEngine,
                    connectedSinceEpochMs = connectedAt,
                    errorMessage = null
                )
            }
            serviceScope.launch { connectionHistory.record(server.id, networkType, true) }
            syncExternalSurfaces(ConnectionStatus.CONNECTED, server.name, selectedEngine.label)
            SanitizedLogBuffer.info(TAG, "Tunnel connected: ${server.name} (${selectedEngine.label})")

            registerNetworkWatcherIfEnabled(settings)

            updateNotification(
                title = getString(R.string.notif_vpn_connected_title, server.name),
                subtitle = "${selectedEngine.label} • ${getString(R.string.notif_vpn_speed, "0 B/s", "0 B/s")}"
            )

            statsCollectorJob?.cancel()
            statsCollectorJob = serviceScope.launch {
                targetCore.stats.collect { sample ->
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
                        subtitle = "${selectedEngine.label} • ${
                            getString(
                                R.string.notif_vpn_speed,
                                Formatters.formatSpeed(sample.downloadBytesPerSec),
                                Formatters.formatSpeed(sample.uploadBytesPerSec)
                            )
                        }"
                    )
                }
            }
        }.onFailure { err ->
            if (err is CancellationException || manualDisconnectRequested) return
            connectionHistory.record(server.id, networkType, false)
            stopActiveCoreAndTun()
            SanitizedLogBuffer.error(TAG, "Connection failed on ${server.name}: ${err.message}")

            // Optional Auto-Failover to next-best reachable server
            if (allowFailover && settings.autoFailover && !manualDisconnectRequested) {
                val alternatives = serverRepository.getCachedServers().filter { it.id != server.id }
                val backup = if (settings.autoSelectMode == AutoSelectMode.SMART) {
                    connectionHistory.rank(alternatives).firstOrNull()
                } else {
                    alternatives.filter { (it.pingMs ?: -1) > 0 }
                        .sortedWith(compareByDescending<VpnServer> { it.stabilityScore }.thenBy { it.pingMs })
                        .firstOrNull()
                }

                if (backup != null) {
                    SanitizedLogBuffer.warn(TAG, "Auto-failover switching to ${backup.name}")
                    connectInternal(backup, isAutoMode = true, allowFailover = false)
                    return
                }
            }

            failConnection(
                message = err.localizedMessage ?: "Failed to start ${selectedEngine.label} tunnel",
                server = server,
                engine = selectedEngine
            )
        }
    }

    private fun registerNetworkWatcherIfEnabled(settings: AppSettings) {
        unregisterNetworkWatcher()
        if (!settings.autoReconnect) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        var seenInitialAvailable = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!seenInitialAvailable) {
                    seenInitialAvailable = true
                    return
                }
                if (manualDisconnectRequested) return
                val target = lastServer ?: return
                if (stateBus.state.value.status == ConnectionStatus.CONNECTED) {
                    SanitizedLogBuffer.info(TAG, "Underlying network changed; scheduling auto-reconnect")
                    scheduleExponentialReconnect(target)
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        }
    }

    private fun scheduleExponentialReconnect(target: VpnServer) {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delaysMs = listOf(2_000L, 4_000L, 8_000L)
            for ((index, waitMs) in delaysMs.withIndex()) {
                delay(waitMs)
                if (manualDisconnectRequested) return@launch
                SanitizedLogBuffer.info(TAG, "Auto-reconnect attempt ${index + 1}/${delaysMs.size}")
                connectInternal(target, isAutoMode = lastAutoMode, allowFailover = index == delaysMs.lastIndex)
                if (stateBus.state.value.status == ConnectionStatus.CONNECTED) {
                    return@launch
                }
            }
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun failConnection(
        message: String,
        server: VpnServer? = null,
        engine: VpnEngineType = VpnEngineType.XRAY
    ) {
        unregisterNetworkWatcher()
        stateBus.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                activeServer = server ?: it.activeServer,
                activeEngine = engine,
                errorMessage = message,
                uploadBytesPerSec = 0L,
                downloadBytesPerSec = 0L,
                connectedSinceEpochMs = null
            )
        }
        syncExternalSurfaces(ConnectionStatus.ERROR, server?.name, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildTunInterface(
        engine: VpnEngineType,
        settings: AppSettings
    ): ParcelFileDescriptor? {
        val ipv4Address = if (engine == VpnEngineType.XRAY) "10.10.14.1" else "172.19.0.1"
        val ipv6Address = if (engine == VpnEngineType.XRAY) "fc00::1" else "fdfe:dcba:9876::1"

        val primaryTunDns = when (settings.dnsPreset) {
            DnsPreset.GOOGLE_DOH -> "8.8.8.8"
            DnsPreset.QUAD9_DOH -> "9.9.9.9"
            DnsPreset.CUSTOM -> {
                val raw = settings.customDnsServer.trim()
                if (raw.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) raw else "1.1.1.1"
            }
            else -> "1.1.1.1"
        }

        val builder = Builder()
            .setSession("${BrandConfig.appName} (${engine.label})")
            .setMtu(settings.mtu.coerceIn(1200, 9000))
            .addAddress(ipv4Address, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(primaryTunDns)
            .addDnsServer("8.8.8.8")

        if (settings.enableIpv6) {
            builder.addAddress(ipv6Address, 126)
            builder.addRoute("::", 0)
            builder.addDnsServer("2606:4700:4700::1111")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        runCatching { builder.addDisallowedApplication(packageName) }

        when (settings.splitTunnelMode) {
            SplitTunnelMode.DISABLED -> Unit
            SplitTunnelMode.BYPASS_SELECTED -> {
                settings.splitTunnelPackages.forEach { pkg ->
                    if (pkg != packageName) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }

            SplitTunnelMode.ONLY_SELECTED -> {
                if (settings.splitTunnelPackages.isNotEmpty()) {
                    settings.splitTunnelPackages.forEach { pkg ->
                        if (pkg != packageName) {
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    }
                }
            }
        }

        return builder.establish()
    }

    private suspend fun recordCurrentSessionIfConnected() {
        val snapshot = stateBus.state.value
        val startedAt = snapshot.connectedSinceEpochMs ?: return
        val server = snapshot.activeServer ?: return
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1_000L)
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAt))
        runCatching {
            serverRepository.recordSessionStat(
                SessionStatRecord(
                    serverId = server.id,
                    serverName = server.name,
                    engineLabel = snapshot.activeEngine.label,
                    startedAtMs = startedAt,
                    durationMs = durationMs,
                    uploadBytes = snapshot.sessionUploadBytes,
                    downloadBytes = snapshot.sessionDownloadBytes,
                    dayKey = dayKey
                )
            )
        }
    }

    private suspend fun stopActiveCoreAndTun() {
        statsCollectorJob?.cancel()
        statsCollectorJob = null

        val core = activeCore
        activeCore = null
        if (core != null) {
            runCatching { core.stop() }
        }

        val pfd = activeTunPfd
        activeTunPfd = null
        if (pfd != null) {
            runCatching { pfd.close() }
        }
    }

    private fun disconnectInternal() {
        connectJob?.cancel()
        reconnectJob?.cancel()
        unregisterNetworkWatcher()
        stateBus.update { it.copy(status = ConnectionStatus.DISCONNECTING) }
        serviceScope.launch {
            recordCurrentSessionIfConnected()
            stopActiveCoreAndTun()
            SanitizedLogBuffer.info(TAG, "Tunnel disconnected cleanly")
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
            syncExternalSurfaces(ConnectionStatus.DISCONNECTED, null, null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun syncExternalSurfaces(
        status: ConnectionStatus,
        serverName: String?,
        subtitle: String?
    ) {
        PasarGuardWidgetProvider.saveSnapshotAndRefresh(
            context = this,
            status = status,
            serverName = serverName,
            subtitle = subtitle
        )
        VpnQuickSettingsTileService.requestListeningStateCompat(this)
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
            disconnectIntent = disconnectIntent,
            disguise = disguiseMode
        )
    }

    override fun onRevoke() {
        manualDisconnectRequested = true
        disconnectInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        reconnectJob?.cancel()
        unregisterNetworkWatcher()
        runBlocking {
            recordCurrentSessionIfConnected()
            stopActiveCoreAndTun()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VpnService"
        const val ACTION_CONNECT = "com.pasarguard.vpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.pasarguard.vpn.action.DISCONNECT"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_RAW_URI = "extra_raw_uri"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_AUTO_MODE = "extra_auto_mode"
    }
}
