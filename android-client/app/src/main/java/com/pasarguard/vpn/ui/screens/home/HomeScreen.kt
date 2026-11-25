package com.pasarguard.vpn.ui.screens.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import com.pasarguard.vpn.ui.screens.support.ServiceIncidentBanner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.pasarguard.vpn.ui.components.ConnectHeroButton
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.ProgressRingCard
import com.pasarguard.vpn.ui.components.PulsingDot
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

                // Top Dynamic Status Capsule
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.glassSurface)
                            .border(1.dp, colors.glassBorderTop, RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusColor = when (state.session.status) {
                            ConnectionStatus.CONNECTED -> colors.statusConnected
                            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> colors.heroEnd
                            ConnectionStatus.ERROR -> colors.statusDanger
                            ConnectionStatus.DISCONNECTED -> colors.textMuted
                        }
                        PulsingDot(
                            color = statusColor,
                            isPulsing = state.session.status == ConnectionStatus.CONNECTED ||
                                state.session.status == ConnectionStatus.CONNECTING
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = buildString {
                                append(
                                    stringResource(
                                        when (state.session.status) {
                                            ConnectionStatus.CONNECTED -> R.string.status_connected
                                            ConnectionStatus.CONNECTING -> R.string.status_connecting
                                            ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
                                            ConnectionStatus.ERROR -> R.string.status_error
                                            ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
                                        }
                                    )
                                )
                                if (state.session.status == ConnectionStatus.CONNECTED ||
                                    state.session.status == ConnectionStatus.CONNECTING
                                ) {
                                    append(" • ")
                                    append(state.session.activeEngine.label)
                                }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textPrimary
                        )
                    }

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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Campaign,
                                contentDescription = null,
                                tint = colors.heroEnd,
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
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textMuted)
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
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
                                    color = colors.heroEnd,
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
                                status = if (state.choosingServer) ConnectionStatus.CONNECTING else state.session.status,
                                errorMessage = state.session.errorMessage,
                                onOpenDiagnostics = onOpenDiagnostics,
                                onConnectTap = handleConnectTap
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1.2f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
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
                        status = if (state.choosingServer) ConnectionStatus.CONNECTING else state.session.status,
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
    val colors = PasarGuardTheme.colors
    ConnectHeroButton(status = status, onClick = onConnectTap)
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = stringResource(
            when (status) {
                ConnectionStatus.CONNECTED -> R.string.tap_to_disconnect
                ConnectionStatus.CONNECTING -> R.string.connection_cancel
                ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
                ConnectionStatus.ERROR -> R.string.connection_retry
                ConnectionStatus.DISCONNECTED -> R.string.tap_to_connect
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary
    )
    if (status == ConnectionStatus.ERROR) {
        androidx.compose.material3.TextButton(onClick = onOpenDiagnostics) {
            Text(stringResource(R.string.support_check_connection))
        }
    }
    if (errorMessage != null) {
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.labelSmall,
            color = colors.statusDanger,
            modifier = Modifier.padding(horizontal = Spacing.md)
        )
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

    // Active Server Selector Pill
    val activeServer = state.session.activeServer
        ?: state.servers.firstOrNull { it.id == state.settings.selectedServerId }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        onClick = onOpenServers
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
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
                        text = activeServer?.let {
                            val pingLabel = if ((it.pingMs ?: -1) > 0) {
                                " • ${Formatters.toPersianDigitsIfNeeded("${it.pingMs} ms", persianDigits)}"
                            } else ""
                            val jsonTag = if (!it.rawXrayConfigJson.isNullOrBlank()) " • Xray JSON" else ""
                            "${it.protocol.label}$jsonTag$pingLabel"
                        } ?: stringResource(R.string.home_no_servers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = stringResource(R.string.home_select_server),
                tint = colors.textSecondary
            )
        }
    }

    Spacer(Modifier.height(Spacing.md))

    if (state.session.status == ConnectionStatus.CONNECTED) {
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryCell(
                    icon = Icons.Rounded.ArrowDownward,
                    tint = colors.statusConnected,
                    label = stringResource(R.string.home_download),
                    value = Formatters.formatSpeed(state.session.downloadBytesPerSec, persianDigits),
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    icon = Icons.Rounded.ArrowUpward,
                    tint = colors.heroEnd,
                    label = stringResource(R.string.home_upload),
                    value = Formatters.formatSpeed(state.session.uploadBytesPerSec, persianDigits),
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    icon = Icons.Rounded.Timer,
                    tint = colors.accentViolet,
                    label = stringResource(R.string.home_session),
                    value = Formatters.formatDuration(elapsedMs, persianDigits),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
    }

    // Pro Quick Tools Bento Row (Diagnostics, Profiles, Statistics)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        QuickToolChip(
            icon = Icons.Rounded.NetworkCheck,
            label = stringResource(R.string.diagnostics_short),
            onClick = onOpenDiagnostics,
            modifier = Modifier.weight(1f)
        )
        QuickToolChip(
            icon = Icons.Rounded.AccountCircle,
            label = stringResource(R.string.profiles_short),
            onClick = onOpenProfiles,
            modifier = Modifier.weight(1f)
        )
        QuickToolChip(
            icon = Icons.Rounded.BarChart,
            label = stringResource(R.string.stats_short),
            onClick = onOpenStatistics,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(Modifier.height(Spacing.md))

    // Bento Dual Progress Rings
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        val trafficValue = when {
            sub == null -> "—"
            sub.isUnlimitedTraffic -> "∞"
            else -> Formatters.formatBytes(sub.remainingTrafficBytes ?: 0L, persianDigits)
        }
        val trafficSub = when {
            sub == null -> "—"
            sub.isUnlimitedTraffic -> stringResource(R.string.home_unlimited)
            else -> "${Formatters.formatBytes(sub.usedTrafficBytes, persianDigits)} / ${
                Formatters.formatBytes(sub.dataLimitBytes ?: 0L, persianDigits)
            }"
        }
        val trafficFraction = sub?.remainingTrafficFraction ?: 1f

        ProgressRingCard(
            title = stringResource(R.string.home_traffic_remaining),
            primaryValue = trafficValue,
            subtitle = trafficSub,
            progress = trafficFraction,
            isWarning = sub != null && !sub.isUnlimitedTraffic && trafficFraction < 0.30f,
            isDanger = sub != null && !sub.isUnlimitedTraffic && trafficFraction < 0.15f,
            modifier = Modifier.weight(1f)
        )

        val remDays = sub?.remainingDays()
        val daysPrimary = when {
            sub == null -> "—"
            sub.status == SubscriptionStatus.ON_HOLD -> stringResource(R.string.home_on_hold)
            sub.status == SubscriptionStatus.EXPIRED -> stringResource(R.string.home_expired)
            remDays == null -> "∞"
            else -> Formatters.toPersianDigitsIfNeeded(
                stringResource(R.string.home_days_unit, remDays),
                persianDigits
            )
        }
        val daysSub = sub?.expireEpochSeconds?.let {
            Formatters.formatEpochDate(
                epochSeconds = it,
                locale = if (isPersian) Locale("fa") else Locale.US,
                calendarSystem = calendarSystem,
                persianDigits = persianDigits
            )
        } ?: stringResource(R.string.home_unlimited)
        val timeFraction = sub?.remainingTimeFraction() ?: 1f

        ProgressRingCard(
            title = stringResource(R.string.home_days_remaining),
            primaryValue = daysPrimary,
            subtitle = daysSub,
            progress = timeFraction,
            isWarning = remDays != null && remDays in 2..5,
            isDanger = sub?.status == SubscriptionStatus.EXPIRED || (remDays != null && remDays <= 1),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickToolChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    GlassCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.heroEnd, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TelemetryCell(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        AnimatedContent(targetState = value, label = "telemetryVal") { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
    }
}
