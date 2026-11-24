package com.pasarguard.vpn.ui.screens.home

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.semantics.stateDescription
import com.pasarguard.vpn.ui.components.LiveThroughputChart
import com.pasarguard.vpn.ui.components.rememberThroughput
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import com.pasarguard.vpn.ui.components.ServerLocationMark
import com.pasarguard.vpn.ui.components.serverCountryLabel
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
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val subscription: SubscriptionInfo? = null,
    val servers: List<VpnServer> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val session: VpnSessionState = VpnSessionState(),
    val isRefreshing: Boolean = false,
    val snackbarError: String? = null
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
    private val transientError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        subscriptionRepository.observeSubscription(),
        serverRepository.observeServers(),
        settingsRepository.settings,
        vpnController.sessionState,
        isRefreshing,
        transientError
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            subscription = values[0] as SubscriptionInfo?,
            servers = values[1] as List<VpnServer>,
            settings = values[2] as AppSettings,
            session = values[3] as VpnSessionState,
            isRefreshing = values[4] as Boolean,
            snackbarError = values[5] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    init {
        refreshSubscription()
        viewModelScope.launch {
            val settings = settingsRepository.getSnapshot()
            if (settings.autoTestOnAppOpen) {
                launch { serverRepository.pingAllServers() }
            }
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
        val current = vpnController.sessionState.value.status
        if (current == ConnectionStatus.CONNECTED || current == ConnectionStatus.CONNECTING ||
            current == ConnectionStatus.DISCONNECTING) {
            viewModelScope.launch { vpnController.disconnect() }
        } else {
            connectNow()
        }
    }

    fun connectNow() {
        viewModelScope.launch {
            transientError.value = null
            val preferredId = settingsRepository.getSnapshot().selectedServerId
            val (server, isAuto) = selectBestServer(preferredId)
            if (server == null) {
                syncSubscription()
                val (retried, autoRetry) = selectBestServer(preferredId)
                if (retried != null) {
                    vpnController.connect(retried, autoRetry)
                } else {
                    transientError.value = "NO_SERVERS"
                }
                return@launch
            }
            vpnController.connect(server, isAuto)
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

    var permissionDenied by remember { mutableStateOf(false) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        permissionDenied = result.resultCode != Activity.RESULT_OK
        if (!permissionDenied) {
            viewModel.connectNow()
        }
    }

    val handleConnectTap = {
        permissionDenied = false
        val current = state.session.status
        if (current == ConnectionStatus.CONNECTED || current == ConnectionStatus.CONNECTING) {
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
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {

            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(Spacing.sm))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(BrandConfig.appName, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f), color = colors.textPrimary)
                    IconButton(onClick = viewModel::refreshSubscription) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.servers_refresh))
                    }
                }
                HeroConnectSection(
                    status = state.session.status,
                    errorMessage = if (permissionDenied) stringResource(R.string.connection_permission_denied)
                        else state.session.errorMessage,
                    elapsedMs = elapsedMs,
                    persianDigits = persianDigits,
                    onOpenServers = onOpenServers,
                    onConnectTap = handleConnectTap
                )
                Spacer(Modifier.height(Spacing.md))
                SelectedServerCard(state, persianDigits, onOpenServers)
                if (state.session.status == ConnectionStatus.CONNECTED) {
                    Spacer(Modifier.height(Spacing.sm))
                    ConnectionTelemetry(state, elapsedMs, persianDigits, onOpenStatistics)
                }
                Spacer(Modifier.height(Spacing.md))

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

                HomeDashboardBody(
                    state = state, persianDigits = persianDigits,
                    onOpenRenewal = onOpenRenewal,
                    calendarSystem = calendarSystem, isPersian = isPersian,
                    onOpenDiagnostics = onOpenDiagnostics, onOpenProfiles = onOpenProfiles,
                    onOpenStatistics = onOpenStatistics
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroConnectSection(
    status: ConnectionStatus,
    errorMessage: String?,
    elapsedMs: Long,
    persianDigits: Boolean,
    onOpenServers: () -> Unit,
    onConnectTap: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val statusText = stringResource(when (status) {
        ConnectionStatus.CONNECTED -> R.string.status_connected
        ConnectionStatus.CONNECTING -> R.string.status_connecting
        ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
        ConnectionStatus.ERROR -> R.string.status_error
        ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
    })
    val connected = status == ConnectionStatus.CONNECTED
    val pending = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING
    val scheme = MaterialTheme.colorScheme
    val action = stringResource(when (status) {
        ConnectionStatus.CONNECTED -> R.string.connection_disconnect
        ConnectionStatus.CONNECTING -> R.string.connection_cancel
        ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
        ConnectionStatus.ERROR -> R.string.connection_retry
        ConnectionStatus.DISCONNECTED -> R.string.connection_connect
    })
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.lg)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(statusText, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = when {
                    connected -> colors.statusConnected
                    status == ConnectionStatus.ERROR -> colors.statusDanger
                    else -> scheme.onSurface
                })
            Spacer(Modifier.height(Spacing.md))
            val orbColor = when {
                connected -> colors.statusConnected
                pending -> colors.statusWarning
                status == ConnectionStatus.ERROR -> colors.statusDanger
                else -> scheme.primary
            }
            val iconColor = if (orbColor.luminance() > .42f) Color(0xFF071C16) else Color.White
            Surface(
                modifier = Modifier.sizeIn(maxWidth = 188.dp).fillMaxWidth().heightIn(min = 188.dp)
                    .clip(CircleShape)
                    .combinedClickable(enabled = status != ConnectionStatus.DISCONNECTING, role = Role.Button,
                        onClick = onConnectTap, onLongClick = onOpenServers)
                    .semantics {
                        contentDescription = action
                        stateDescription = statusText
                    },
                shape = CircleShape,
                color = orbColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val tint = iconColor
                    if (pending) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = tint, strokeWidth = 2.dp)
                    } else {
                        val icon = when {
                            connected -> Icons.Rounded.Shield
                            status == ConnectionStatus.ERROR -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.PowerSettingsNew
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(54.dp))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(action, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            if (connected) {
                Text(Formatters.formatDuration(elapsedMs, persianDigits),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            }
            if (errorMessage != null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = colors.statusDanger)
            }
        }
    }
}

@Composable
private fun SelectedServerCard(state: HomeUiState, persianDigits: Boolean, onOpenServers: () -> Unit) {
    val colors = PasarGuardTheme.colors
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
                ServerLocationMark(activeServer?.countryCode)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = if (state.settings.selectedServerId == null) {
                            activeServer?.name ?: stringResource(
                                if (state.servers.isEmpty()) R.string.home_no_servers else R.string.connection_automatic
                            )
                        } else {
                            activeServer?.name ?: stringResource(R.string.home_select_server)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeServer?.let {
                            val pingLabel = if ((it.pingMs ?: -1) > 0) {
                                " • ${Formatters.toPersianDigitsIfNeeded("${it.pingMs} ms", persianDigits)}"
                            } else " · ${stringResource(R.string.server_latency_unknown)}"
                            val jsonTag = if (!it.rawXrayConfigJson.isNullOrBlank()) " • Xray JSON" else ""
                            "${serverCountryLabel(it.countryCode)} · ${it.protocol.label}$jsonTag$pingLabel"
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
}

@Composable
private fun HomeDashboardBody(
    state: HomeUiState,
    onOpenRenewal: () -> Unit,
    persianDigits: Boolean,
    calendarSystem: com.pasarguard.vpn.domain.model.CalendarSystem,
    isPersian: Boolean,
    onOpenDiagnostics: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenStatistics: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val sub = state.subscription

    if (sub != null) {
        val traffic = if (sub.isUnlimitedTraffic) stringResource(R.string.home_unlimited)
            else Formatters.formatBytes(sub.remainingTrafficBytes ?: 0L, persianDigits)
        val expiry = sub.expireEpochSeconds?.let {
            Formatters.formatEpochDate(it, if (isPersian) Locale("fa") else Locale.US, calendarSystem, persianDigits)
        } ?: stringResource(R.string.home_unlimited)
        GlassCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_traffic_remaining), style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary)
            Text(traffic, style = MaterialTheme.typography.titleMedium)
            Text(expiry, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            TextButton(onClick = onOpenRenewal) { Text(stringResource(R.string.sub_renew_cta)) }
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
}
@Composable
private fun ConnectionTelemetry(
    state: HomeUiState,
    elapsedMs: Long,
    persianDigits: Boolean,
    onOpenStatistics: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val samples = rememberThroughput(state.session.connectedSinceEpochMs,
        state.session.downloadBytesPerSec, state.session.uploadBytesPerSec)
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenStatistics) {
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
                tint = MaterialTheme.colorScheme.tertiary,
                label = stringResource(R.string.home_upload),
                value = Formatters.formatSpeed(state.session.uploadBytesPerSec, persianDigits),
                modifier = Modifier.weight(1f)
            )
            TelemetryCell(
                icon = Icons.Rounded.Timer,
                tint = colors.textSecondary,
                label = stringResource(R.string.home_session),
                value = Formatters.formatDuration(elapsedMs, persianDigits),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        LiveThroughputChart(samples, stringResource(R.string.home_live_throughput))
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
