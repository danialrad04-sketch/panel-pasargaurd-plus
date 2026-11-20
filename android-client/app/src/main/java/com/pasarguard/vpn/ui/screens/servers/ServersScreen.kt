package com.pasarguard.vpn.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.model.ProxyProtocol
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.domain.usecase.SelectBestServerUseCase
import com.pasarguard.vpn.domain.usecase.SyncSubscriptionUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ServerFilter { ALL, FAVORITES, VLESS, VMESS, TROJAN, SHADOWSOCKS }

data class ServersUiState(
    val servers: List<VpnServer> = emptyList(),
    val selectedServerId: String? = null,
    val query: String = "",
    val filter: ServerFilter = ServerFilter.ALL,
    val isTestingPings: Boolean = false
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
    private val isTestingPings = MutableStateFlow(false)

    val uiState: StateFlow<ServersUiState> = combine(
        serverRepository.observeServers(),
        settingsRepository.settings,
        query,
        filter,
        isTestingPings
    ) { servers, settings, q, f, testing ->
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
            matchesQuery && matchesFilter
        }
        ServersUiState(
            servers = filtered,
            selectedServerId = settings.selectedServerId,
            query = q,
            filter = f,
            isTestingPings = testing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    fun onQueryChange(value: String) { query.value = value }
    fun onFilterChange(newFilter: ServerFilter) { filter.value = newFilter }

    fun toggleFavorite(serverId: String) {
        viewModelScope.launch { serverRepository.toggleFavorite(serverId) }
    }

    fun testAllPings() {
        if (isTestingPings.value) return
        viewModelScope.launch {
            isTestingPings.value = true
            serverRepository.pingAllServers()
            isTestingPings.value = false
        }
    }

    fun refreshFromPanel() {
        viewModelScope.launch {
            syncSubscription()
            testAllPings()
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

@Composable
fun ServersScreen(
    onServerChosen: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            // Header Row
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
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.glassSurface)
                        .border(1.dp, colors.glassBorderTop, RoundedCornerShape(16.dp))
                        .clickable(enabled = !state.isTestingPings) { viewModel.testAllPings() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isTestingPings) {
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
                            if (state.isTestingPings) R.string.servers_ping_testing
                            else R.string.servers_test_all
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )

            Spacer(Modifier.height(Spacing.sm))

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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

            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // Pinned Auto (Best Ping) smart card
                item(key = "auto_best") {
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
                                    text = stringResource(R.string.servers_auto_subtitle),
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

                if (state.servers.isEmpty()) {
                    item(key = "empty") {
                        EmptyStateView(
                            title = stringResource(R.string.servers_empty_title),
                            description = stringResource(R.string.servers_empty_desc),
                            actionLabel = stringResource(R.string.servers_refresh),
                            onAction = { viewModel.refreshFromPanel() }
                        )
                    }
                } else {
                    items(items = state.servers, key = { it.id }) { server ->
                        ServerListCard(
                            server = server,
                            isSelected = state.selectedServerId == server.id,
                            onSelect = { viewModel.selectServer(server, onServerChosen) },
                            onToggleFavorite = { viewModel.toggleFavorite(server.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerListCard(
    server: VpnServer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val pingColor = when {
        server.pingMs == null -> colors.textMuted
        server.pingMs < 0 -> colors.statusDanger
        server.pingMs < 130 -> colors.statusConnected
        server.pingMs < 280 -> colors.statusWarning
        else -> colors.statusDanger
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
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = server.flagEmoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(Spacing.md))
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.heroStart.copy(alpha = 0.20f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = server.protocol.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.heroEnd
                        )
                    }
                    Text(
                        text = when {
                            server.pingMs == null -> "—"
                            server.pingMs < 0 -> stringResource(R.string.servers_ping_timeout)
                            else -> stringResource(R.string.servers_ping_ms, server.pingMs)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = pingColor
                    )
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
