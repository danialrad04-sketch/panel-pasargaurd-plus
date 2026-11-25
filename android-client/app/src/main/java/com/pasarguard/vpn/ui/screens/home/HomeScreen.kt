package com.pasarguard.vpn.ui.screens.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import com.pasarguard.vpn.ui.screens.support.ServiceIncidentBanner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.model.VpnSessionState
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.domain.usecase.SelectBestServerUseCase
import com.pasarguard.vpn.domain.usecase.SyncSubscriptionUseCase
import com.pasarguard.vpn.ui.components.CoachTipBanner
import com.pasarguard.vpn.ui.components.ConnectionDial
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.LocalCalendarSystem
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val subscription: SubscriptionInfo? = null,
    val servers: List<VpnServer> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val session: VpnSessionState = VpnSessionState(),
    val isRefreshing: Boolean = false,
    val snackbarError: String? = null,
    val choosingServer: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val vpnController: VpnController,
    private val selectBestServer: SelectBestServerUseCase,
    private val syncSubscription: SyncSubscriptionUseCase
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private var connectionJob: Job? = null
    private val choosingServer = MutableStateFlow(false)
    private val transientError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        subscriptionRepository.observeSubscription(),
        serverRepository.observeServers(),
        settingsRepository.settings,
        vpnController.sessionState,
        isRefreshing,
        transientError,
        choosingServer
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            subscription = values[0] as SubscriptionInfo?,
            servers = values[1] as List<VpnServer>,
            settings = values[2] as AppSettings,
            session = values[3] as VpnSessionState,
            isRefreshing = values[4] as Boolean,
            snackbarError = values[5] as String?,
            choosingServer = values[6] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    init {
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            refreshSubscription()
            val settings = settingsRepository.getSnapshot()
            if (settings.autoTestOnAppOpen) serverRepository.pingAllServers()
        }
        viewModelScope.launch {
            val settings = settingsRepository.getSnapshot()
            if (settings.autoConnect &&
                vpnController.hasVpnPermission() &&
                vpnController.sessionState.value.status == ConnectionStatus.DISCONNECTED
            ) {
                connectNow()
            }
        }
    }

    fun dismissAnnouncement(announceText: String) {
        viewModelScope.launch {
            settingsRepository.setDismissedAnnouncementHash(announceText.hashCode().toString())
        }
    }

    fun dismissCoachTip() {
        viewModelScope.launch {
            settingsRepository.setCoachTipsDismissed(true)
        }
    }

    fun refreshSubscription() {
        viewModelScope.launch {
            isRefreshing.value = true
            syncSubscription().onFailure { err ->
                transientError.value = err.localizedMessage
            }
            isRefreshing.value = false
        }
    }

    fun toggleConnection() {
        if (connectionJob?.isActive == true) {
            connectionJob?.cancel()
            return
        }
        when (vpnController.sessionState.value.status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> viewModelScope.launch { vpnController.disconnect() }
            ConnectionStatus.DISCONNECTING -> Unit
            else -> connectNow()
        }
    }

    fun connectNow() {
        if (connectionJob?.isActive == true) return
        choosingServer.value = true
        connectionJob = viewModelScope.launch {
            try {
                transientError.value = null
                val preferredId = settingsRepository.getSnapshot().selectedServerId
                var (server, isAuto) = selectBestServer(preferredId)
                if (server == null) {
                    syncSubscription()
                    val retry = selectBestServer(preferredId)
                    server = retry.first
                    isAuto = retry.second
                }
                if (server == null) transientError.value = "NO_SERVERS"
                else vpnController.connect(server, isAuto)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                transientError.value = "NO_SERVERS"
            } finally {
                choosingServer.value = false
            }
        }
    }
}

@Composable
fun HomeScreen(
    onOpenServers: () -> Unit,
    onOpenRenewal: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    toggleVpnTrigger: Long = 0L,
    onConsumeToggleVpn: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    val connectionStatus = if (state.choosingServer) ConnectionStatus.CONNECTING else state.session.status
    val persianDigits = LocalPersianDigits.current
    val calendarSystem = LocalCalendarSystem.current
    val isPersian = state.settings.language == AppLanguage.PERSIAN

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectNow()
        }
    }

    val handleConnectTap = {
        val current = state.session.status
        if (state.choosingServer || current == ConnectionStatus.CONNECTED ||
            current == ConnectionStatus.CONNECTING || current == ConnectionStatus.DISCONNECTING) {
            viewModel.toggleConnection()
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.connectNow()
            }
        }
    }

    // Handle 1-tap connect trigger from Home-Screen Widget or Quick Settings Tile
    LaunchedEffect(toggleVpnTrigger) {
        if (toggleVpnTrigger > 0L) {
            onConsumeToggleVpn()
            handleConnectTap()
        }
    }

    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.session.status, state.session.connectedSinceEpochMs) {
        val startedAt = state.session.connectedSinceEpochMs
        if (state.session.status == ConnectionStatus.CONNECTED && startedAt != null) {
            while (true) {
                elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                delay(1_000L)
            }
        } else {
            elapsedMs = 0L
        }
    }


    val sub = state.subscription
    val daysLeft = sub?.remainingDays()
    val showRenewWarning = sub != null && (
        sub.status == SubscriptionStatus.EXPIRED ||
            sub.status == SubscriptionStatus.LIMITED ||
            (daysLeft != null && daysLeft <= BrandConfig.expiryWarningDaysThreshold) ||
            (!sub.isUnlimitedTraffic && (sub.remainingTrafficFraction * 100f) <= BrandConfig.lowTrafficWarningPercentThreshold)
        )
    val announceText = sub?.announce?.takeIf {
        it.isNotBlank() && it.hashCode().toString() != state.settings.dismissedAnnouncementHash
    }

    DeepSpaceBackground {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            val isWide = maxWidth >= 720.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = 112.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(Spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = { viewModel.refreshSubscription() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.servers_refresh),
                            tint = colors.textPrimary
                        )
                    }
                }

                // Dismissible Panel Announcement Banner
                if (announceText != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Campaign,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = announceText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.dismissAnnouncement(announceText) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), tint = colors.textSecondary)
                            }
                        }
                    }
                }

                // First-run Coach Tip Banner
                if (!state.settings.coachTipsDismissed) {
                    Spacer(Modifier.height(Spacing.sm))
                    CoachTipBanner(
                        title = stringResource(R.string.coach_tip_title),
                        body = stringResource(R.string.coach_tip_body),
                        onDismiss = viewModel::dismissCoachTip
                    )
                }

                if (showRenewWarning) {
                    Spacer(Modifier.height(Spacing.sm))
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = colors.statusWarning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = stringResource(R.string.home_renew_banner),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = onOpenRenewal) {
                                Text(
                                    text = stringResource(R.string.home_renew_now),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.lg))

                ServiceIncidentBanner("connection", state.session.activeServer
                    ?: state.servers.firstOrNull { it.id == state.settings.selectedServerId })

                // Adaptive Layout: 2-pane on wide tablets, single column on phones
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            HeroConnectSection(
                                status = connectionStatus,
                                errorMessage = state.session.errorMessage,
                                onOpenDiagnostics = onOpenDiagnostics,
                                onConnectTap = handleConnectTap
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1.2f)
                        ) {
                            HomeDashboardBody(
                                state = state,
                                elapsedMs = elapsedMs,
                                persianDigits = persianDigits,
                                calendarSystem = calendarSystem,
                                isPersian = isPersian,
                                onOpenServers = onOpenServers,
                                onOpenDiagnostics = onOpenDiagnostics,
                                onOpenProfiles = onOpenProfiles,
                                onOpenStatistics = onOpenStatistics
                            )
                        }
                    }
                } else {
                    HeroConnectSection(
                        status = connectionStatus,
                        errorMessage = state.session.errorMessage,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onConnectTap = handleConnectTap
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    HomeDashboardBody(
                        state = state,
                        elapsedMs = elapsedMs,
                        persianDigits = persianDigits,
                        calendarSystem = calendarSystem,
                        isPersian = isPersian,
                        onOpenServers = onOpenServers,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onOpenProfiles = onOpenProfiles,
                        onOpenStatistics = onOpenStatistics
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroConnectSection(
    status: ConnectionStatus,
    errorMessage: String?,
    onOpenDiagnostics: () -> Unit,
    onConnectTap: () -> Unit
) {
    val statusLabel = when (status) {
        ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
        ConnectionStatus.CONNECTING -> R.string.status_connecting
        ConnectionStatus.CONNECTED -> R.string.status_connected
        ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
        ConnectionStatus.ERROR -> R.string.status_error
    }
    val actionLabel = when (status) {
        ConnectionStatus.DISCONNECTED -> R.string.connection_connect
        ConnectionStatus.CONNECTING -> R.string.connection_cancel
        ConnectionStatus.CONNECTED -> R.string.connection_disconnect
        ConnectionStatus.DISCONNECTING -> null
        ConnectionStatus.ERROR -> R.string.connection_retry
    }
    Text(
        text = stringResource(statusLabel),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
    Spacer(Modifier.height(8.dp))
    ConnectionDial(status = status, actionLabel = actionLabel?.let { stringResource(it) }, onClick = onConnectTap)
    if (status == ConnectionStatus.ERROR) {
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = PasarGuardTheme.colors.statusDanger,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }
        TextButton(onClick = onOpenDiagnostics) {
            Text(stringResource(R.string.support_check_connection))
        }
    }
}

@Composable
private fun HomeDashboardBody(
    state: HomeUiState,
    elapsedMs: Long,
    persianDigits: Boolean,
    calendarSystem: com.pasarguard.vpn.domain.model.CalendarSystem,
    isPersian: Boolean,
    onOpenServers: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenStatistics: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val sub = state.subscription

    val activeServer = state.session.activeServer
        ?: state.servers.firstOrNull { it.id == state.settings.selectedServerId }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        onClick = onOpenServers
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Dns, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = activeServer?.name ?: stringResource(
                            if (state.servers.isEmpty()) R.string.home_select_server else R.string.home_auto_best
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            activeServer != null -> listOfNotNull(
                                activeServer.protocol.label,
                                "Xray JSON".takeIf { !activeServer.rawXrayConfigJson.isNullOrBlank() },
                                state.session.activeEngine.label.takeIf {
                                    state.session.status == ConnectionStatus.CONNECTED &&
                                        it != activeServer.protocol.label
                                },
                                activeServer.pingMs?.takeIf { it > 0 }?.let {
                                    Formatters.toPersianDigitsIfNeeded("$it ms", persianDigits)
                                }
                            ).joinToString(" · ")
                            state.servers.isEmpty() -> stringResource(R.string.home_no_servers)
                            else -> stringResource(R.string.home_auto_server_hint)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = if (activeServer != null) TextDirection.Ltr else TextDirection.Content
                        ),
                        color = colors.textSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.padding(start = 8.dp).size(20.dp)
            )
        }
    }

    Spacer(Modifier.height(Spacing.md))

    if (state.session.status == ConnectionStatus.CONNECTED) {
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryCell(
                    icon = Icons.Rounded.ArrowDownward,
                    label = stringResource(R.string.home_download),
                    value = Formatters.formatSpeed(state.session.downloadBytesPerSec, persianDigits),
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    icon = Icons.Rounded.ArrowUpward,
                    label = stringResource(R.string.home_upload),
                    value = Formatters.formatSpeed(state.session.uploadBytesPerSec, persianDigits),
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    icon = Icons.Rounded.Timer,
                    label = stringResource(R.string.home_session),
                    value = Formatters.formatDuration(elapsedMs, persianDigits),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(4.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            QuickTool(
                icon = Icons.Rounded.NetworkCheck,
                label = stringResource(R.string.diagnostics_short),
                onClick = onOpenDiagnostics,
                modifier = Modifier.weight(1f)
            )
            QuickTool(
                icon = Icons.Rounded.AccountCircle,
                label = stringResource(R.string.profiles_short),
                onClick = onOpenProfiles,
                modifier = Modifier.weight(1f)
            )
            QuickTool(
                icon = Icons.Rounded.BarChart,
                label = stringResource(R.string.stats_short),
                onClick = onOpenStatistics,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (sub != null) {
        Spacer(Modifier.height(Spacing.md))
        val remDays = sub.remainingDays()
        val timeValue = when {
            sub.status == SubscriptionStatus.ON_HOLD -> stringResource(R.string.home_on_hold)
            sub.status == SubscriptionStatus.EXPIRED -> stringResource(R.string.home_expired)
            remDays == null -> stringResource(R.string.home_unlimited)
            else -> Formatters.toPersianDigitsIfNeeded(
                stringResource(R.string.home_days_unit, remDays), persianDigits
            )
        }
        val expiry = sub.expireEpochSeconds?.let {
            Formatters.formatEpochDate(
                epochSeconds = it,
                locale = if (isPersian) Locale("fa") else Locale.US,
                calendarSystem = calendarSystem,
                persianDigits = persianDigits
            )
        }
        val trafficFraction = sub.remainingTrafficFraction
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            QuotaRow(
                label = stringResource(R.string.home_traffic_remaining),
                value = if (sub.isUnlimitedTraffic) stringResource(R.string.home_unlimited) else
                    Formatters.formatBytes(sub.remainingTrafficBytes ?: 0L, persianDigits),
                detail = if (sub.isUnlimitedTraffic) null else stringResource(
                    R.string.home_usage_total,
                    "\u2066${Formatters.formatBytes(sub.usedTrafficBytes, persianDigits)}\u2069",
                    "\u2066${Formatters.formatBytes(sub.dataLimitBytes ?: 0L, persianDigits)}\u2069"
                ),
                fraction = trafficFraction.takeUnless { sub.isUnlimitedTraffic },
                color = when {
                    trafficFraction < 0.15f -> colors.statusDanger
                    trafficFraction < 0.30f -> colors.statusWarning
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            QuotaRow(
                label = stringResource(R.string.home_days_remaining),
                value = timeValue,
                detail = expiry,
                fraction = null,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun QuotaRow(label: String, value: String, detail: String?, fraction: Float?, color: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), color = scheme.onSurface,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End)
    }
    if (detail != null) {
        Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = scheme.onSurfaceVariant)
    }
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
            color = color,
            trackColor = scheme.surfaceVariant
        )
    }
}

@Composable
private fun QuickTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TelemetryCell(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFeatureSettings = "tnum",
                textDirection = TextDirection.Ltr
            ),
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
