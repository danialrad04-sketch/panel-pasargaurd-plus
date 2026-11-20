package com.pasarguard.vpn.ui.screens.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.usecase.SyncSubscriptionUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.components.UsageHistoryChart
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionUiState(
    val subscription: SubscriptionInfo? = null,
    val usagePoints: List<UsagePoint> = emptyList(),
    val language: AppLanguage = AppLanguage.PERSIAN,
    val isSyncing: Boolean = false
)

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    subscriptionRepository: SubscriptionRepository,
    settingsRepository: SettingsRepository,
    private val syncSubscription: SyncSubscriptionUseCase
) : ViewModel() {

    private val isSyncing = MutableStateFlow(false)

    val uiState: StateFlow<SubscriptionUiState> = combine(
        subscriptionRepository.observeSubscription(),
        subscriptionRepository.observeUsageHistory(),
        settingsRepository.settings,
        isSyncing
    ) { sub, usage, settings, syncing ->
        SubscriptionUiState(
            subscription = sub,
            usagePoints = usage,
            language = settings.language,
            isSyncing = syncing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionUiState())

    fun syncNow() {
        if (isSyncing.value) return
        viewModelScope.launch {
            isSyncing.value = true
            syncSubscription()
            isSyncing.value = false
        }
    }
}

@Composable
fun SubscriptionScreen(
    onOpenRenewal: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val sub = state.subscription
    val isPersian = state.language == AppLanguage.PERSIAN

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = 112.dp)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.sub_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
                IconButton(onClick = { viewModel.syncNow() }, enabled = !state.isSyncing) {
                    if (state.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.sub_sync_now),
                            tint = colors.textPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Hero Subscription Passport Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.heroStart.copy(alpha = 0.92f),
                                colors.heroEnd.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .padding(Spacing.lg)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.sub_account),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                            Text(
                                text = sub?.username ?: "—",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        StatusBadgePill(status = sub?.status ?: SubscriptionStatus.UNKNOWN)
                    }

                    Spacer(Modifier.height(Spacing.lg))

                    val usedStr = Formatters.toPersianDigitsIfNeeded(
                        Formatters.formatBytes(sub?.usedTrafficBytes ?: 0L),
                        isPersian
                    )
                    val usageText = when {
                        sub == null -> "—"
                        sub.isUnlimitedTraffic -> stringResource(R.string.sub_used_unlimited, usedStr)
                        else -> stringResource(
                            R.string.sub_used_of,
                            usedStr,
                            Formatters.toPersianDigitsIfNeeded(
                                Formatters.formatBytes(sub.dataLimitBytes ?: 0L),
                                isPersian
                            )
                        )
                    }
                    Text(
                        text = usageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(Spacing.sm))

                    val usedFraction = if (sub == null || sub.isUnlimitedTraffic) {
                        0.15f
                    } else {
                        (1f - sub.remainingTrafficFraction).coerceIn(0.02f, 1f)
                    }
                    LinearProgressIndicator(
                        progress = { usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )

                    if (sub != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(
                                R.string.sub_last_synced,
                                Formatters.formatRelativeSync(sub.lastSyncedEpochMs)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Plan Details Bento Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                DetailMiniCard(
                    icon = Icons.Rounded.CalendarMonth,
                    label = stringResource(R.string.sub_expiry_label),
                    value = sub?.expireEpochSeconds?.let { Formatters.formatEpochDate(it) }
                        ?: stringResource(R.string.home_unlimited),
                    modifier = Modifier.weight(1f)
                )
                DetailMiniCard(
                    icon = Icons.Rounded.Repeat,
                    label = stringResource(R.string.sub_reset_label),
                    value = sub?.resetStrategy?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                DetailMiniCard(
                    icon = Icons.Rounded.Devices,
                    label = stringResource(R.string.sub_hwid_label),
                    value = sub?.hwidLimit?.toString() ?: "∞",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // Usage History Chart Card
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Text(
                    text = stringResource(R.string.sub_usage_chart_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Spacing.sm))
                UsageHistoryChart(
                    points = state.usagePoints,
                    emptyText = stringResource(R.string.sub_usage_empty)
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            GradientButton(
                text = stringResource(R.string.sub_renew_cta),
                onClick = onOpenRenewal,
                leadingIcon = Icons.Rounded.AutoAwesome,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatusBadgePill(status: SubscriptionStatus) {
    val labelRes = when (status) {
        SubscriptionStatus.ACTIVE -> R.string.sub_status_active
        SubscriptionStatus.ON_HOLD -> R.string.sub_status_on_hold
        SubscriptionStatus.LIMITED -> R.string.sub_status_limited
        SubscriptionStatus.EXPIRED -> R.string.sub_status_expired
        SubscriptionStatus.DISABLED -> R.string.sub_status_disabled
        SubscriptionStatus.UNKNOWN -> R.string.sub_status_active
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
    }
}

@Composable
private fun DetailMiniCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    GlassCard(modifier = modifier, cornerRadius = 20.dp) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.heroEnd, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1
        )
    }
}
