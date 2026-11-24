package com.pasarguard.vpn.ui.screens.servers

import com.pasarguard.vpn.ui.components.PasarSheetHandle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import com.pasarguard.vpn.ui.components.PasarDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.AutoSelectMode
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.model.PingHistoryPoint
import com.pasarguard.vpn.domain.model.PingProgressState
import com.pasarguard.vpn.domain.model.PingScoring
import com.pasarguard.vpn.domain.model.PingTestMode
import com.pasarguard.vpn.domain.model.ProxyProtocol
import com.pasarguard.vpn.domain.model.ServerSortMode
import com.pasarguard.vpn.domain.model.StabilityTier
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.domain.usecase.SelectBestServerUseCase
import com.pasarguard.vpn.domain.usecase.SyncSubscriptionUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.components.PingSparklineChart
import com.pasarguard.vpn.ui.components.ShimmerPlaceholder
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.util.QrCodeGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.pasarguard.vpn.ui.components.ServerLocationMark
import com.pasarguard.vpn.ui.components.serverCountryCode
import com.pasarguard.vpn.ui.components.serverCountryLabel
import javax.inject.Inject
import kotlin.math.roundToInt

enum class ServerFilter { ALL, FAVORITES, VLESS, VMESS, TROJAN, SHADOWSOCKS }

data class ServersUiState(
    val servers: List<VpnServer> = emptyList(),
    val selectedServerId: String? = null,
    val query: String = "",
    val filter: ServerFilter = ServerFilter.ALL,
    val settings: AppSettings = AppSettings(),
    val pingProgress: PingProgressState = PingProgressState(),
    val isRefreshing: Boolean = false,
    val recentlyUsedIds: List<String> = emptyList(),
    val hasMeasuredServers: Boolean = false
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val vpnController: VpnController,
    private val selectBestServer: SelectBestServerUseCase,
    private val syncSubscription: SyncSubscriptionUseCase
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ServerFilter.ALL)
    private val isRefreshing = MutableStateFlow(false)

    private val _selectedDetailHistory = MutableStateFlow<List<PingHistoryPoint>>(emptyList())
    val selectedDetailHistory: StateFlow<List<PingHistoryPoint>> = _selectedDetailHistory.asStateFlow()

    val uiState: StateFlow<ServersUiState> = combine(
        serverRepository.observeServers(),
        settingsRepository.settings,
        query,
        filter,
        serverRepository.pingProgress,
        isRefreshing,
        serverRepository.observeSessionStats()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val servers = values[0] as List<VpnServer>
        val settings = values[1] as AppSettings
        val q = values[2] as String
        val f = values[3] as ServerFilter
        val progress = values[4] as PingProgressState
        val refreshing = values[5] as Boolean
        @Suppress("UNCHECKED_CAST")
        val sessions = values[6] as List<com.pasarguard.vpn.domain.model.SessionStatRecord>
        val recent = sessions.sortedByDescending { it.startedAtMs }.map { it.serverId }.distinct().take(4)


        val filtered = servers.filter { srv ->
            val matchesQuery = q.isBlank() ||
                srv.name.contains(q, ignoreCase = true) ||
                srv.countryCode.contains(q, ignoreCase = true) ||
                srv.protocol.label.contains(q, ignoreCase = true)
            val matchesFilter = when (f) {
                ServerFilter.ALL -> true
                ServerFilter.FAVORITES -> srv.isFavorite
                ServerFilter.VLESS -> srv.protocol == ProxyProtocol.VLESS
                ServerFilter.VMESS -> srv.protocol == ProxyProtocol.VMESS
                ServerFilter.TROJAN -> srv.protocol == ProxyProtocol.TROJAN
                ServerFilter.SHADOWSOCKS -> srv.protocol == ProxyProtocol.SHADOWSOCKS
            }
            val matchesDead = !settings.hideDeadServers || srv.pingMs == null || srv.pingMs > 0
            matchesQuery && matchesFilter && matchesDead
        }

        val sorted = when (settings.serverSortMode) {
            ServerSortMode.PING -> filtered.sortedWith(
                compareBy<VpnServer> {
                    when {
                        it.pingMs == null -> 2
                        it.pingMs <= 0 -> 3
                        else -> 1
                    }
                }.thenBy { it.pingMs ?: Int.MAX_VALUE }
            )

            ServerSortMode.STABILITY -> filtered.sortedWith(
                compareByDescending<VpnServer> { it.stabilityScore }
                    .thenBy { if ((it.pingMs ?: -1) > 0) it.pingMs else Int.MAX_VALUE }
            )

            ServerSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            ServerSortMode.COUNTRY -> filtered.sortedWith(
                compareBy<VpnServer> { it.countryCode }.thenBy { it.name.lowercase() }
            )
            ServerSortMode.FAVORITES -> filtered.sortedWith(
                compareByDescending<VpnServer> { it.isFavorite }
                    .thenBy { if ((it.pingMs ?: -1) > 0) it.pingMs else Int.MAX_VALUE }
            )
        }

        ServersUiState(
            servers = sorted,
            selectedServerId = settings.selectedServerId,
            query = q,
            filter = f,
            settings = settings,
            pingProgress = progress,
            isRefreshing = refreshing,
            recentlyUsedIds = recent,
            hasMeasuredServers = servers.any { (it.pingMs ?: 0) > 0 }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    fun onQueryChange(value: String) { query.value = value }
    fun onFilterChange(newFilter: ServerFilter) { filter.value = newFilter }

    fun setSortMode(mode: ServerSortMode) {
        viewModelScope.launch { settingsRepository.setServerSortMode(mode) }
    }

    fun toggleHideDead(hide: Boolean) {
        viewModelScope.launch { settingsRepository.setHideDeadServers(hide) }
    }

    fun setPingTestMode(mode: PingTestMode) {
        viewModelScope.launch { settingsRepository.setPingTestMode(mode) }
    }

    fun setPingTestUrl(url: String) {
        viewModelScope.launch { settingsRepository.setPingTestUrl(url) }
    }

    fun addCustomPingUrl(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
        viewModelScope.launch {
            val current = settingsRepository.getSnapshot().customPingUrls
            if (clean !in current) {
                settingsRepository.setCustomPingUrls(current + clean)
            }
            settingsRepository.setPingTestUrl(clean)
        }
    }

    fun removeCustomPingUrl(url: String) {
        viewModelScope.launch {
            val current = settingsRepository.getSnapshot().customPingUrls
            settingsRepository.setCustomPingUrls(current.filterNot { it == url })
        }
    }

    fun setPingSamples(samples: Int) {
        viewModelScope.launch { settingsRepository.setPingSamplesPerServer(samples) }
    }

    fun setPingTimeout(sec: Int) {
        viewModelScope.launch { settingsRepository.setPingTimeoutSec(sec) }
    }

    fun setPingConcurrency(concurrency: Int) {
        viewModelScope.launch { settingsRepository.setPingMaxConcurrency(concurrency) }
    }

    fun setAutoSelectMode(mode: AutoSelectMode) {
        viewModelScope.launch { settingsRepository.setAutoSelectMode(mode) }
    }

    fun setAutoTestBeforeAutoSelect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBeforeAutoSelect(enabled) }
    }

    fun toggleFavorite(serverId: String) {
        viewModelScope.launch { serverRepository.toggleFavorite(serverId) }
    }

    fun testAllPings() {
        if (uiState.value.pingProgress.isRunning) return
        viewModelScope.launch { serverRepository.pingAllServers() }
    }

    fun cancelPingTest() {
        serverRepository.cancelBatchPing()
    }

    fun testSingleServer(server: VpnServer) {
        viewModelScope.launch {
            serverRepository.pingServer(server)
            loadHistory(server.id)
        }
    }

    fun loadHistory(serverId: String) {
        viewModelScope.launch {
            _selectedDetailHistory.value = serverRepository.observePingHistory(serverId).first()
        }
    }

    fun importManualLinks(raw: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = serverRepository.importManualLinks(raw)
            onResult(count)
        }
    }

    fun deleteManualServer(serverId: String) {
        viewModelScope.launch { serverRepository.deleteServer(serverId) }
    }

    fun refreshFromPanel() {
        viewModelScope.launch {
            isRefreshing.value = true
            syncSubscription()
            isRefreshing.value = false
            serverRepository.pingAllServers()
        }
    }

    fun selectAutoBest(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSelectedServerId(null)
            if (vpnController.sessionState.value.status == ConnectionStatus.CONNECTED) {
                val (best, _) = selectBestServer(null)
                if (best != null) vpnController.connect(best, true)
            }
            onDone()
        }
    }

    fun selectServer(server: VpnServer, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSelectedServerId(server.id)
            if (vpnController.sessionState.value.status == ConnectionStatus.CONNECTED) {
                vpnController.connect(server, false)
            }
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServersScreen(
    onServerChosen: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detailHistory by viewModel.selectedDetailHistory.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current
    val context = LocalContext.current

    var showPingSettingsSheet by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var inspectedServerId by remember { mutableStateOf<String?>(null) }
    var qrServer by remember { mutableStateOf<VpnServer?>(null) }

    val inspectedServer = state.servers.firstOrNull { it.id == inspectedServerId }

    LaunchedEffect(inspectedServerId) {
        inspectedServerId?.let { viewModel.loadHistory(it) }
    }

    DeepSpaceBackground {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refreshFromPanel,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.md)
            ) {
                Spacer(Modifier.height(Spacing.sm))

                // Header Row: Title + Import + Ping Settings + Test All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.servers_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showImportSheet = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.servers_import_title),
                                tint = colors.textPrimary
                            )
                        }
                        IconButton(onClick = { showPingSettingsSheet = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.ping_settings_title),
                                tint = colors.heroEnd
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.glassSurface)
                                .border(1.dp, colors.glassBorderTop, RoundedCornerShape(16.dp))
                                .clickable {
                                    if (state.pingProgress.isRunning) viewModel.cancelPingTest()
                                    else viewModel.testAllPings()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (state.pingProgress.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.heroEnd
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    tint = colors.heroEnd,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (state.pingProgress.isRunning) R.string.action_cancel
                                    else R.string.servers_test_all
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textPrimary
                            )
                        }
                    }
                }

                // Live Batch Ping Progress Bar ("12/40 tested")
                if (state.pingProgress.isRunning && state.pingProgress.total > 0) {
                    Spacer(Modifier.height(Spacing.xs))
                    val done = state.pingProgress.completed
                    val total = state.pingProgress.total
                    val fraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = Formatters.toPersianDigitsIfNeeded(
                                    stringResource(R.string.servers_ping_progress, done, total),
                                    persianDigits
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = state.settings.pingTestMode.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.heroEnd
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.heroEnd
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp)
                )

                Spacer(Modifier.height(Spacing.xs))

                // Protocol filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    val chips = listOf(
                        ServerFilter.ALL to stringResource(R.string.servers_filter_all),
                        ServerFilter.FAVORITES to stringResource(R.string.servers_filter_favorites),
                        ServerFilter.VLESS to "VLESS",
                        ServerFilter.VMESS to "VMess",
                        ServerFilter.TROJAN to "Trojan",
                        ServerFilter.SHADOWSOCKS to "SS"
                    )
                    chips.forEach { (f, label) ->
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { viewModel.onFilterChange(f) },
                            label = { Text(label) }
                        )
                    }
                }

                // Sort + Hide Dead chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    listOf(
                        ServerSortMode.PING to R.string.servers_sort_ping,
                        ServerSortMode.STABILITY to R.string.servers_sort_stability,
                        ServerSortMode.FAVORITES to R.string.servers_filter_favorites,
                        ServerSortMode.COUNTRY to R.string.servers_sort_country,
                        ServerSortMode.NAME to R.string.servers_sort_name
                    ).forEach { (mode, resId) ->
                        FilterChip(
                            selected = state.settings.serverSortMode == mode,
                            onClick = { viewModel.setSortMode(mode) },
                            label = { Text(stringResource(resId)) }
                        )
                    }
                    FilterChip(
                        selected = state.settings.hideDeadServers,
                        onClick = { viewModel.toggleHideDead(!state.settings.hideDeadServers) },
                        label = { Text(stringResource(R.string.servers_hide_dead)) }
                    )
                }

                Spacer(Modifier.height(Spacing.xs))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // Pinned Auto smart card
                    if (state.hasMeasuredServers && !state.pingProgress.isRunning && state.query.isBlank()) item(key = "auto_best") {
                        val isAutoSelected = state.selectedServerId == null
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isAutoSelected) Modifier.border(
                                        1.5.dp,
                                        colors.heroEnd,
                                        RoundedCornerShape(22.dp)
                                    ) else Modifier
                                ),
                            cornerRadius = 22.dp,
                            onClick = { viewModel.selectAutoBest(onServerChosen) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = colors.heroEnd,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.servers_auto_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = when (state.settings.autoSelectMode) {
                                            AutoSelectMode.LOWEST_PING -> stringResource(R.string.auto_mode_lowest_ping)
                                            AutoSelectMode.MOST_STABLE -> stringResource(R.string.auto_mode_most_stable)
                                            AutoSelectMode.ROUND_ROBIN_TOP_N -> stringResource(R.string.auto_mode_round_robin)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary
                                    )
                                }
                                if (isAutoSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.heroEnd
                                    )
                                }
                            }
                        }
                    }

                    if (state.isRefreshing && state.servers.isEmpty()) {
                        items(4) { ShimmerPlaceholder(height = 76.dp) }
                    } else if (state.servers.isEmpty()) {
                        item(key = "empty") {
                            EmptyStateView(
                                title = stringResource(R.string.servers_empty_title),
                                description = stringResource(R.string.servers_empty_desc),
                                actionLabel = stringResource(R.string.servers_refresh),
                                onAction = { viewModel.refreshFromPanel() }
                            )
                        }
                    } else {
                        val byId = state.servers.associateBy { it.id }
                        val regularView = state.query.isBlank() && state.filter == ServerFilter.ALL
                        val recent = if (regularView) state.recentlyUsedIds.mapNotNull(byId::get) else emptyList()
                        val pinned = if (regularView) state.servers.filter { it.isFavorite } else emptyList()
                        val list = state.servers.groupBy { serverCountryCode(it.countryCode) }
                        for ((section, sectionServers) in listOf(R.string.server_recent_title to recent, R.string.servers_filter_favorites to pinned)) {
                            if (sectionServers.isEmpty()) continue
                            item(key = "section-$section") { ServerSectionLabel(stringResource(section)) }
                            items(items = sectionServers, key = { "$section-${it.id}" }) { server ->
                                ServerListCard(server, state.selectedServerId == server.id, persianDigits,
                                    onInspect = { inspectedServerId = server.id },
                                    onToggleFavorite = { viewModel.toggleFavorite(server.id) })
                            }
                        }
                        for ((country, servers) in list) {
                            item(key = "country-$country") { ServerSectionLabel(serverCountryLabel(country)) }
                            items(items = servers, key = { "country-${it.id}" }) { server ->
                                ServerListCard(server, state.selectedServerId == server.id, persianDigits,
                                    onInspect = { inspectedServerId = server.id },
                                    onToggleFavorite = { viewModel.toggleFavorite(server.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    // Quick Ping Settings Bottom Sheet
    if (showPingSettingsSheet) {
        PingSettingsBottomSheet(
            settings = state.settings,
            onDismiss = { showPingSettingsSheet = false },
            onSelectMode = viewModel::setPingTestMode,
            onSelectUrl = viewModel::setPingTestUrl,
            onAddCustomUrl = viewModel::addCustomPingUrl,
            onRemoveCustomUrl = viewModel::removeCustomPingUrl,
            onSamplesChange = viewModel::setPingSamples,
            onTimeoutChange = viewModel::setPingTimeout,
            onConcurrencyChange = viewModel::setPingConcurrency,
            onAutoSelectModeChange = viewModel::setAutoSelectMode,
            onAutoTestBeforeSelectChange = viewModel::setAutoTestBeforeAutoSelect
        )
    }

    // Manual Import Bottom Sheet
    if (showImportSheet) {
        ManualImportBottomSheet(
            onDismiss = { showImportSheet = false },
            onImportText = { raw ->
                viewModel.importManualLinks(raw) { imported ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.servers_import_result, imported),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (imported > 0) showImportSheet = false
                }
            }
        )
    }

    // Server Detail Bottom Sheet (Sparkline + Quality Metrics + Actions)
    if (inspectedServer != null) {
        ServerDetailBottomSheet(
            server = inspectedServer,
            history = detailHistory,
            persianDigits = persianDigits,
            onDismiss = { inspectedServerId = null },
            onConnect = {
                inspectedServerId = null
                viewModel.selectServer(inspectedServer, onServerChosen)
            },
            onTestAgain = { viewModel.testSingleServer(inspectedServer) },
            onToggleFavorite = { viewModel.toggleFavorite(inspectedServer.id) },
            onCopyLink = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("server", inspectedServer.rawUri))
                Toast.makeText(context, R.string.action_copied, Toast.LENGTH_SHORT).show()
            },
            onShowQr = { qrServer = inspectedServer },
            onDelete = if (inspectedServer.groupName == "Manual") {
                {
                    viewModel.deleteManualServer(inspectedServer.id)
                    inspectedServerId = null
                }
            } else null
        )
    }

    // Offline QR Code Dialog
    if (qrServer != null) {
        val qrBitmap = remember(qrServer?.rawUri) {
            QrCodeGenerator.generateBitmap(qrServer?.rawUri.orEmpty(), 600)
        }
        PasarDialog(
            onDismissRequest = { qrServer = null },
            title = { Text(qrServer?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        )
                    } else {
                        Text(stringResource(R.string.servers_qr_unavailable))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { qrServer = null }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun ServerSectionLabel(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs))
}

@Composable
private fun ServerListCard(
    server: VpnServer,
    isSelected: Boolean,
    persianDigits: Boolean,
    onInspect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val tierColor = when (server.stabilityTier) {
        StabilityTier.EXCELLENT -> colors.statusConnected
        StabilityTier.GOOD -> colors.heroEnd
        StabilityTier.POOR -> colors.statusWarning
        StabilityTier.DEAD -> colors.statusDanger
        StabilityTier.UNTESTED -> colors.textMuted
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    1.5.dp,
                    colors.heroEnd,
                    RoundedCornerShape(20.dp)
                ) else Modifier
            ),
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onInspect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServerLocationMark(server.countryCode)
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.heroStart.copy(alpha = 0.20f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${server.protocol.label} • ${server.transportLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.heroEnd
                        )
                    }
                    Text(
                        text = when {
                            server.pingMs == null -> stringResource(R.string.server_latency_unknown)
                            server.pingMs < 0 -> stringResource(R.string.servers_ping_timeout)
                            else -> Formatters.toPersianDigitsIfNeeded(
                                stringResource(R.string.servers_ping_ms, server.pingMs),
                                persianDigits
                            )
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = tierColor
                    )
                    if (server.stabilityScore > 0) {
                        Text(
                            text = Formatters.toPersianDigitsIfNeeded("${server.stabilityScore}%", persianDigits),
                            style = MaterialTheme.typography.labelSmall,
                            color = tierColor
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (server.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(R.string.servers_filter_favorites),
                    tint = if (server.isFavorite) colors.statusWarning else colors.textMuted
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ServerDetailBottomSheet(
    server: VpnServer,
    history: List<PingHistoryPoint>,
    persianDigits: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onTestAgain: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyLink: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val colors = PasarGuardTheme.colors
    ModalBottomSheet(dragHandle = { PasarSheetHandle() },
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCanvas,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    ServerLocationMark(server.countryCode)
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${server.protocol.label} • ${server.transportLabel} • ${server.securityLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.heroEnd
                        )
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (server.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = if (server.isFavorite) colors.statusWarning else colors.textMuted
                    )
                }
            }

            // Metrics Grid: Avg, Min/Max, Jitter, Loss, Stability
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailMetric(
                        label = stringResource(R.string.ping_metric_avg),
                        value = server.pingMs?.takeIf { it > 0 }?.let {
                            Formatters.toPersianDigitsIfNeeded("$it ms", persianDigits)
                        } ?: "—"
                    )
                    DetailMetric(
                        label = stringResource(R.string.ping_metric_jitter),
                        value = Formatters.toPersianDigitsIfNeeded("${server.jitterMs} ms", persianDigits)
                    )
                    DetailMetric(
                        label = stringResource(R.string.ping_metric_loss),
                        value = Formatters.toPersianDigitsIfNeeded("${server.packetLossPct}%", persianDigits)
                    )
                    DetailMetric(
                        label = stringResource(R.string.ping_metric_stability),
                        value = Formatters.toPersianDigitsIfNeeded("${server.stabilityScore}%", persianDigits)
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider(color = colors.glassBorderTop)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "\u2066${server.address}:${server.port}\u2069  ·  ${server.groupName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }

            // 10-Point Latency Sparkline
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Text(
                    text = stringResource(R.string.server_detail_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                PingSparklineChart(
                    history = history,
                    emptyText = stringResource(R.string.server_detail_no_history)
                )
            }

            // Action Buttons
            GradientButton(
                text = stringResource(R.string.action_connect),
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedButton(onClick = onTestAgain, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.server_detail_test_again))
                }
                OutlinedButton(onClick = onCopyLink, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_copy))
                }
                OutlinedButton(onClick = onShowQr, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("QR")
                }
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(14.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = colors.statusDanger,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_delete), color = colors.statusDanger)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String) {
    val colors = PasarGuardTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PingSettingsBottomSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSelectMode: (PingTestMode) -> Unit,
    onSelectUrl: (String) -> Unit,
    onAddCustomUrl: (String) -> Unit,
    onRemoveCustomUrl: (String) -> Unit,
    onSamplesChange: (Int) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onConcurrencyChange: (Int) -> Unit,
    onAutoSelectModeChange: (AutoSelectMode) -> Unit,
    onAutoTestBeforeSelectChange: (Boolean) -> Unit
) {
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current
    var customUrlInput by remember { mutableStateOf("") }

    ModalBottomSheet(dragHandle = { PasarSheetHandle() },
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCanvas,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.ping_settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )

            // Test Mode
            Text(stringResource(R.string.ping_mode_label), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(
                    PingTestMode.THROUGH_CORE to R.string.ping_mode_core,
                    PingTestMode.TCP to R.string.ping_mode_tcp,
                    PingTestMode.MULTI_URL to R.string.ping_mode_multi
                ).forEach { (mode, resId) ->
                    FilterChip(
                        selected = settings.pingTestMode == mode,
                        onClick = { onSelectMode(mode) },
                        label = { Text(stringResource(resId)) }
                    )
                }
            }

            // Built-in + Custom Test URLs
            Text(stringResource(R.string.ping_url_label), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            val allUrls = PingScoring.DEFAULT_TEST_URLS + settings.customPingUrls
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                allUrls.forEach { url ->
                    val shortLabel = url.removePrefix("https://").removePrefix("http://").substringBefore("/")
                    val isCustom = url in settings.customPingUrls
                    FilterChip(
                        selected = settings.pingTestUrl == url,
                        onClick = { onSelectUrl(url) },
                        label = { Text(shortLabel, maxLines = 1) },
                        trailingIcon = if (isCustom) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onRemoveCustomUrl(url) }
                                )
                            }
                        } else null
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedTextField(
                    value = customUrlInput,
                    onValueChange = { customUrlInput = it },
                    placeholder = { Text("https://example.com/generate_204") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        onAddCustomUrl(customUrlInput)
                        customUrlInput = ""
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_add))
                }
            }

            // Sliders: Samples, Timeout, Concurrency
            Text(
                text = Formatters.toPersianDigitsIfNeeded(
                    stringResource(R.string.ping_samples_label, settings.pingSamplesPerServer),
                    persianDigits
                ),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            Slider(
                value = settings.pingSamplesPerServer.toFloat(),
                onValueChange = { onSamplesChange(it.roundToInt()) },
                valueRange = 1f..5f,
                steps = 3
            )

            Text(
                text = Formatters.toPersianDigitsIfNeeded(
                    stringResource(R.string.ping_timeout_label, settings.pingTimeoutSec),
                    persianDigits
                ),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            Slider(
                value = settings.pingTimeoutSec.toFloat(),
                onValueChange = { onTimeoutChange(it.roundToInt()) },
                valueRange = 2f..10f,
                steps = 7
            )

            Text(
                text = Formatters.toPersianDigitsIfNeeded(
                    stringResource(R.string.ping_concurrency_label, settings.pingMaxConcurrency),
                    persianDigits
                ),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            Slider(
                value = settings.pingMaxConcurrency.toFloat(),
                onValueChange = { onConcurrencyChange(it.roundToInt()) },
                valueRange = 1f..10f,
                steps = 8
            )

            // Auto-Select Mode
            Text(stringResource(R.string.auto_select_mode_label), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(
                    AutoSelectMode.LOWEST_PING to R.string.auto_mode_lowest_ping,
                    AutoSelectMode.MOST_STABLE to R.string.auto_mode_most_stable,
                    AutoSelectMode.ROUND_ROBIN_TOP_N to R.string.auto_mode_round_robin
                ).forEach { (mode, resId) ->
                    FilterChip(
                        selected = settings.autoSelectMode == mode,
                        onClick = { onAutoSelectModeChange(mode) },
                        label = { Text(stringResource(resId)) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.ping_auto_test_before_select),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.autoTestBeforeAutoSelect,
                    onCheckedChange = onAutoTestBeforeSelectChange
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualImportBottomSheet(
    onDismiss: () -> Unit,
    onImportText: (String) -> Unit
) {
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    var rawText by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                if (content.isNotBlank()) onImportText(content)
            }
        }
    }

    ModalBottomSheet(dragHandle = { PasarSheetHandle() },
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCanvas,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.servers_import_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                placeholder = { Text("vless://... / vmess://... / trojan://... / ss://...") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (clip.isNotBlank()) rawText = clip
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_paste))
                }
                OutlinedButton(
                    onClick = { fileLauncher.launch(arrayOf("text/*", "application/json", "*/*")) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.servers_import_file))
                }
            }
            GradientButton(
                text = stringResource(R.string.servers_import_action),
                enabled = rawText.isNotBlank(),
                onClick = { onImportText(rawText) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
