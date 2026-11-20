package com.pasarguard.vpn.ui.screens.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
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
import com.pasarguard.vpn.ui.components.ConnectHeroButton
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.ProgressRingCard
import com.pasarguard.vpn.ui.components.PulsingDot
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
import javax.inject.Inject

data class HomeUiState(
    val subscription: SubscriptionInfo? = null,
    val servers: List<VpnServer> = emptyList(),
    val selectedServerId: String? = null,
    val session: VpnSessionState = VpnSessionState(),
    val language: AppLanguage = AppLanguage.PERSIAN,
    val isRefreshing: Boolean = false,
    val snackbarError: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    subscriptionRepository: SubscriptionRepository,
    serverRepository: ServerRepository,
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
            selectedServerId = (values[2] as com.pasarguard.vpn.domain.model.AppSettings).selectedServerId,
            session = values[3] as VpnSessionState,
            language = (values[2] as com.pasarguard.vpn.domain.model.AppSettings).language,
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
            if (settings.autoConnect &&
                vpnController.hasVpnPermission() &&
                vpnController.sessionState.value.status == ConnectionStatus.DISCONNECTED
            ) {
                connectNow()
            }
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
        if (current == ConnectionStatus.CONNECTED || current == ConnectionStatus.CONNECTING) {
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
                // Attempt quick sync before failing
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
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    val isPersian = state.language == AppLanguage.PERSIAN

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectNow()
        }
    }

    val handleConnectTap = {
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

    // Live session timer ticker
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

    val glowColor = when (state.session.status) {
        ConnectionStatus.CONNECTED -> colors.statusConnected
        ConnectionStatus.ERROR -> colors.statusDanger
        else -> colors.heroEnd
    }

    val sub = state.subscription
    val daysLeft = sub?.remainingDays()
    val showRenewWarning = sub != null && (
        sub.status == SubscriptionStatus.EXPIRED ||
            sub.status == SubscriptionStatus.LIMITED ||
            (daysLeft != null && daysLeft <= BrandConfig.expiryWarningDaysThreshold) ||
            (!sub.isUnlimitedTraffic && (sub.remainingTrafficFraction * 100f) <= BrandConfig.lowTrafficWarningPercentThreshold)
        )

    DeepSpaceBackground(accentGlowColor = glowColor) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = 112.dp), // clearance for FloatingPillBottomDock
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
                        text = stringResource(
                            when (state.session.status) {
                                ConnectionStatus.CONNECTED -> R.string.status_connected
                                ConnectionStatus.CONNECTING -> R.string.status_connecting
                                ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
                                ConnectionStatus.ERROR -> R.string.status_error
                                ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
                            }
                        ),
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

            // Animated Hero Connect Button
            ConnectHeroButton(
                status = state.session.status,
                onClick = handleConnectTap
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = stringResource(
                    if (state.session.status == ConnectionStatus.CONNECTED) R.string.tap_to_disconnect
                    else R.string.tap_to_connect
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            if (state.session.errorMessage != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = state.session.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.statusDanger
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            // Active Server Selector Pill
            val activeServer = state.session.activeServer
                ?: state.servers.firstOrNull { it.id == state.selectedServerId }
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
                        Text(
                            text = activeServer?.flagEmoji ?: "⚡",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Column {
                            Text(
                                text = if (state.selectedServerId == null) {
                                    stringResource(R.string.home_auto_best)
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
                                    val pingLabel = if ((it.pingMs ?: -1) > 0) " • ${it.pingMs} ms" else ""
                                    "${it.protocol.label}$pingLabel"
                                } ?: stringResource(R.string.home_no_servers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(R.string.home_select_server),
                        tint = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Live Telemetry Strip: Download / Upload / Session Timer
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TelemetryCell(
                        icon = Icons.Rounded.ArrowDownward,
                        tint = colors.statusConnected,
                        label = stringResource(R.string.home_download),
                        value = Formatters.toPersianDigitsIfNeeded(
                            Formatters.formatSpeed(state.session.downloadBytesPerSec),
                            isPersian
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryCell(
                        icon = Icons.Rounded.ArrowUpward,
                        tint = colors.heroEnd,
                        label = stringResource(R.string.home_upload),
                        value = Formatters.toPersianDigitsIfNeeded(
                            Formatters.formatSpeed(state.session.uploadBytesPerSec),
                            isPersian
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryCell(
                        icon = Icons.Rounded.Timer,
                        tint = colors.accentViolet,
                        label = stringResource(R.string.home_session),
                        value = Formatters.toPersianDigitsIfNeeded(
                            Formatters.formatDuration(elapsedMs),
                            isPersian
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Bento Dual Progress Rings: Remaining Traffic & Remaining Days
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                val trafficValue = when {
                    sub == null -> "—"
                    sub.isUnlimitedTraffic -> "∞"
                    else -> Formatters.toPersianDigitsIfNeeded(
                        Formatters.formatBytes(sub.remainingTrafficBytes ?: 0L),
                        isPersian
                    )
                }
                val trafficSub = when {
                    sub == null -> "—"
                    sub.isUnlimitedTraffic -> stringResource(R.string.home_unlimited)
                    else -> Formatters.toPersianDigitsIfNeeded(
                        "${Formatters.formatBytes(sub.usedTrafficBytes)} / ${Formatters.formatBytes(sub.dataLimitBytes ?: 0L)}",
                        isPersian
                    )
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
                        isPersian
                    )
                }
                val daysSub = sub?.expireEpochSeconds?.let { Formatters.formatEpochDate(it) }
                    ?: stringResource(R.string.home_unlimited)
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
    }
}

@Composable
private fun TelemetryCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
