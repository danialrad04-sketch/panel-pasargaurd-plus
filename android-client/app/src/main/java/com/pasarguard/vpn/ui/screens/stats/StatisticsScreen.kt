package com.pasarguard.vpn.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.ui.components.LiveThroughputChart
import com.pasarguard.vpn.ui.components.rememberThroughput
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.SessionStatRecord
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    vpnController: VpnController
) : ViewModel() {

    val liveSession = vpnController.sessionState

    val sessions: StateFlow<List<SessionStatRecord>> = serverRepository.observeSessionStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() {
        viewModelScope.launch { serverRepository.clearSessionStats() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val history by viewModel.sessions.collectAsStateWithLifecycle()
    val live by viewModel.liveSession.collectAsStateWithLifecycle()
    var rangeDays by rememberSaveable { mutableStateOf(7) }
    var windowSeconds by rememberSaveable { mutableStateOf(60) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val cutoff = LocalDate.now().minusDays(rangeDays - 1L)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val sessions = history.filter { it.startedAtMs >= cutoff }
    val liveSamples = if (live.status == ConnectionStatus.CONNECTED) {
        rememberThroughput(live.connectedSinceEpochMs, live.downloadBytesPerSec, live.uploadBytesPerSec, 900)
    } else null
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current

    val totalDownload = sessions.sumOf { it.downloadBytes }
    val totalUpload = sessions.sumOf { it.uploadBytes }
    val totalDurationMs = sessions.sumOf { it.durationMs }

    val topServers = sessions
        .groupBy { it.serverName }
        .map { (name, list) -> name to list.sumOf { it.downloadBytes + it.uploadBytes } }
        .sortedByDescending { it.second }
        .take(5)
    val maxServerBytes = (topServers.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_history)) },
            text = { Text(stringResource(R.string.stats_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clearAll() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    DeepSpaceBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md),
            contentPadding = PaddingValues(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = colors.textPrimary
                            )
                        }
                        Text(
                            text = stringResource(R.string.stats_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary
                        )
                    }
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(R.string.stats_clear_history),
                                tint = colors.statusDanger
                            )
                        }
                    }
                }
            }

            if (liveSamples != null) item(key = "live") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_live_title), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(60, 300, 900).forEach { seconds ->
                            FilterChip(selected = windowSeconds == seconds, onClick = { windowSeconds = seconds },
                                label = { Text(stringResource(R.string.stats_minutes, Formatters.formatNumber(seconds / 60L, persianDigits))) })
                        }
                    }
                    LiveThroughputChart(liveSamples, stringResource(R.string.stats_live_title), windowSeconds)
                }
            }
            item(key = "range") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(1, 7, 30, 90).forEach { days ->
                        FilterChip(selected = rangeDays == days, onClick = { rangeDays = days },
                            label = { Text(if (days == 1) stringResource(R.string.stats_today)
                                else stringResource(R.string.stats_days, Formatters.formatNumber(days.toLong(), persianDigits))) })
                    }
                }
            }

            // Summary Bento Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    StatBentoCard(
                        title = stringResource(R.string.home_download),
                        value = Formatters.formatBytes(totalDownload, persianDigits),
                        modifier = Modifier.weight(1f)
                    )
                    StatBentoCard(
                        title = stringResource(R.string.home_upload),
                        value = Formatters.formatBytes(totalUpload, persianDigits),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    StatBentoCard(
                        title = stringResource(R.string.stats_total_time),
                        value = Formatters.formatDuration(totalDurationMs, persianDigits),
                        modifier = Modifier.weight(1f)
                    )
                    StatBentoCard(
                        title = stringResource(R.string.stats_sessions_count),
                        value = Formatters.formatNumber(sessions.size.toLong(), persianDigits),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (sessions.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Rounded.BarChart,
                        title = stringResource(R.string.stats_empty_title),
                        description = stringResource(R.string.stats_empty_desc)
                    )
                }
            } else {
                // Most-used servers bar breakdown
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                        Text(
                            text = stringResource(R.string.stats_top_servers),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.height(Spacing.md))
                        topServers.forEach { (name, bytes) ->
                            val frac = (bytes.toFloat() / maxServerBytes.toFloat()).coerceIn(0.06f, 1f)
                            Column(modifier = Modifier.padding(bottom = Spacing.sm)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = Formatters.formatBytes(bytes, persianDigits),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.heroEnd
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(colors.textMuted.copy(alpha = 0.2f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(frac)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                Brush.horizontalGradient(listOf(colors.heroStart, colors.heroEnd))
                                            )
                                    )
                                }
                            }
                        }
                    }
                }

                // Recent Sessions
                item {
                    Text(
                        text = stringResource(R.string.stats_recent_sessions),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary
                    )
                }

                items(items = sessions.take(30), key = { it.id }) { rec ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rec.serverName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${rec.dayKey} • ${rec.engineLabel} • ${
                                        Formatters.formatDuration(rec.durationMs, persianDigits)
                                    }",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                            Text(
                                text = Formatters.formatBytes(rec.downloadBytes + rec.uploadBytes, persianDigits),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = colors.heroEnd
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBentoCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    GlassCard(modifier = modifier, cornerRadius = 20.dp) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
