package com.pasarguard.vpn.ui.screens.stats

import androidx.compose.foundation.background
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
    private val serverRepository: ServerRepository
) : ViewModel() {

    val sessions: StateFlow<List<SessionStatRecord>> = serverRepository.observeSessionStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() {
        viewModelScope.launch { serverRepository.clearSessionStats() }
    }
}

@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
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
                    if (sessions.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearAll) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                tint = colors.statusDanger
                            )
                        }
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
