package com.pasarguard.vpn.ui.screens.subscription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.ConnectedDevice
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.model.SupportTicketRequest
import com.pasarguard.vpn.domain.model.UsageForecast
import com.pasarguard.vpn.domain.model.UsageForecastCalculator
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.domain.repository.AccountActionsRepository
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.usecase.SyncSubscriptionUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.components.UsageHistoryChart
import com.pasarguard.vpn.ui.theme.LocalCalendarSystem
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.util.QrCodeGenerator
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

data class SubscriptionUiState(
    val subscription: SubscriptionInfo? = null,
    val usagePoints: List<UsagePoint> = emptyList(),
    val forecast: UsageForecast = UsageForecast(0L, null, 0L, null, false, null),
    val devices: List<ConnectedDevice> = emptyList(),
    val isLoadingDevices: Boolean = false,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val isSyncing: Boolean = false,
    val pendingOrderCount: Int = 0,
    val feedbackMessage: String? = null
)

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    settingsRepository: SettingsRepository,
    paymentRepository: PaymentRepository,
    private val accountActionsRepository: AccountActionsRepository,
    private val syncSubscription: SyncSubscriptionUseCase
) : ViewModel() {

    private val isSyncing = MutableStateFlow(false)
    private val devicesFlow = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    private val loadingDevicesFlow = MutableStateFlow(false)
    private val feedbackFlow = MutableStateFlow<String?>(null)

    private val baseFlow = combine(
        subscriptionRepository.observeSubscription(),
        subscriptionRepository.observeUsageHistory(),
        settingsRepository.settings,
        paymentRepository.observeOrders()
    ) { sub, usage, settings, orders ->
        val pendingCount = orders.count { !it.status.isTerminal }
        Triple(sub to usage, settings.language, pendingCount)
    }

    val uiState: StateFlow<SubscriptionUiState> = combine(
        baseFlow,
        isSyncing,
        devicesFlow,
        loadingDevicesFlow,
        feedbackFlow
    ) { base, syncing, devices, loadingDev, feedback ->
        val (sub, usage) = base.first
        val forecast = UsageForecastCalculator.calculate(sub, usage)
        SubscriptionUiState(
            subscription = sub,
            usagePoints = usage,
            forecast = forecast,
            devices = devices,
            isLoadingDevices = loadingDev,
            language = base.second,
            isSyncing = syncing,
            pendingOrderCount = base.third,
            feedbackMessage = feedback
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

    fun loadDevices() {
        viewModelScope.launch {
            loadingDevicesFlow.value = true
            accountActionsRepository.listConnectedDevices()
                .onSuccess { devicesFlow.value = it }
            loadingDevicesFlow.value = false
        }
    }

    fun removeDevice(deviceId: String, isPersian: Boolean) {
        viewModelScope.launch {
            accountActionsRepository.removeDevice(deviceId).onSuccess { res ->
                feedbackFlow.value = if (isPersian) res.messageFa else res.messageEn
                if (res.success) loadDevices()
            }
        }
    }

    fun requestNewLink(isPersian: Boolean) {
        viewModelScope.launch {
            accountActionsRepository.requestNewSubscriptionLink().onSuccess { res ->
                feedbackFlow.value = if (isPersian) res.messageFa else res.messageEn
            }
        }
    }

    fun submitSupportTicket(subject: String, message: String, isPersian: Boolean) {
        viewModelScope.launch {
            accountActionsRepository.submitSupportTicket(SupportTicketRequest(subject, message))
                .onSuccess { res ->
                    feedbackFlow.value = if (isPersian) res.messageFa else res.messageEn
                }
        }
    }

    fun clearFeedback() {
        feedbackFlow.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onOpenRenewal: () -> Unit,
    onOpenOrders: () -> Unit = {},
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    val persianDigits = LocalPersianDigits.current
    val calendarSystem = LocalCalendarSystem.current
    val sub = state.subscription
    val isPersian = state.language == AppLanguage.PERSIAN
    val locale = if (isPersian) Locale("fa") else Locale.US

    var chartRangeDays by remember { mutableIntStateOf(7) }
    var showQrSheet by remember { mutableStateOf(false) }
    var showDevicesSheet by remember { mutableStateOf(false) }
    var showSupportSheet by remember { mutableStateOf(false) }
    var confirmNewLink by remember { mutableStateOf(false) }

    // Live 1-minute countdown ticker for expiry card
    var nowEpochSec by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowEpochSec = System.currentTimeMillis() / 1000L
        }
    }

    LaunchedEffect(state.feedbackMessage) {
        val msg = state.feedbackMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        viewModel.clearFeedback()
    }

    val isExpiredOrLimited = sub != null && (
        sub.status == SubscriptionStatus.EXPIRED ||
            sub.status == SubscriptionStatus.LIMITED ||
            sub.status == SubscriptionStatus.DISABLED
        )

    DeepSpaceBackground {
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { viewModel.syncNow() },
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = 118.dp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenOrders) {
                            Icon(
                                imageVector = Icons.Rounded.ReceiptLong,
                                contentDescription = stringResource(R.string.orders_title),
                                tint = colors.heroEnd
                            )
                        }
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
                }

                Spacer(Modifier.height(Spacing.sm))

                // Expired / Limited prominent state banner (works even while VPN is OFF)
                if (isExpiredOrLimited) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, colors.statusDanger.copy(alpha = 0.7f), RoundedCornerShape(24.dp)),
                        cornerRadius = 24.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = colors.statusDanger,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        if (sub?.status == SubscriptionStatus.LIMITED) {
                                            R.string.sub_limited_banner_title
                                        } else {
                                            R.string.sub_expired_banner_title
                                        }
                                    ),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = colors.statusDanger
                                )
                                Text(
                                    text = stringResource(R.string.sub_expired_banner_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                        GradientButton(
                            text = stringResource(R.string.sub_renew_now_cta),
                            onClick = onOpenRenewal,
                            leadingIcon = Icons.Rounded.AutoAwesome,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                // Hero Subscription Passport Card with Animated Ring & Live Countdown
                val usedFractionTarget = when {
                    sub == null -> 0f
                    sub.isUnlimitedTraffic -> 0.18f
                    else -> (1f - sub.remainingTrafficFraction).coerceIn(0.02f, 1f)
                }
                val animatedRing by animateFloatAsState(
                    targetValue = usedFractionTarget,
                    animationSpec = tween(durationMillis = 850),
                    label = "sub_usage_ring"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    colors.heroStart.copy(alpha = 0.94f),
                                    colors.heroFillEnd.copy(alpha = 0.86f)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sub?.profileTitle?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.sub_account),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = sub?.username ?: "—",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusBadgePill(status = sub?.status ?: SubscriptionStatus.UNKNOWN)
                        }

                        Spacer(Modifier.height(Spacing.md))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            // Animated circular data ring
                            Box(
                                modifier = Modifier.size(86.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                                    drawArc(
                                        color = Color.White.copy(alpha = 0.22f),
                                        startAngle = -90f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        style = stroke
                                    )
                                    drawArc(
                                        color = Color.White,
                                        startAngle = -90f,
                                        sweepAngle = 360f * animatedRing,
                                        useCenter = false,
                                        style = stroke
                                    )
                                }
                                val centerLabel = when {
                                    sub == null -> "—"
                                    sub.isUnlimitedTraffic -> "∞"
                                    else -> Formatters.toPersianDigitsIfNeeded(
                                        "${(usedFractionTarget * 100).toInt()}%",
                                        persianDigits
                                    )
                                }
                                Text(
                                    text = centerLabel,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                val usedStr = Formatters.formatBytes(sub?.usedTrafficBytes ?: 0L, persianDigits)
                                val usageText = when {
                                    sub == null -> "—"
                                    sub.isUnlimitedTraffic -> stringResource(R.string.sub_used_unlimited, usedStr)
                                    else -> stringResource(
                                        R.string.sub_used_of,
                                        usedStr,
                                        Formatters.formatBytes(sub.dataLimitBytes ?: 0L, persianDigits)
                                    )
                                }
                                Text(
                                    text = usageText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White
                                )

                                Spacer(Modifier.height(4.dp))

                                val countdownLabel = formatLiveCountdown(sub, nowEpochSec, isPersian, persianDigits)
                                Text(
                                    text = countdownLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f)
                                )

                                if (sub != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.sub_last_synced,
                                            Formatters.formatRelativeSync(sub.lastSyncedEpochMs, persianDigits)
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.75f)
                                    )
                                }
                            }
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
                        value = sub?.expireEpochSeconds?.let {
                            Formatters.formatEpochDate(it, locale, calendarSystem, persianDigits)
                        } ?: stringResource(R.string.home_unlimited),
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
                        value = sub?.hwidLimit?.let { Formatters.formatNumber(it.toLong(), persianDigits) } ?: "∞",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(Spacing.md))

                // Usage Insights & Forecast Card
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.sub_usage_chart_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = chartRangeDays == 7,
                                onClick = { chartRangeDays = 7 },
                                label = {
                                    Text(
                                        Formatters.toPersianDigitsIfNeeded(
                                            if (isPersian) "۷ روز" else "7d",
                                            persianDigits
                                        )
                                    )
                                }
                            )
                            FilterChip(
                                selected = chartRangeDays == 30,
                                onClick = { chartRangeDays = 30 },
                                label = {
                                    Text(
                                        Formatters.toPersianDigitsIfNeeded(
                                            if (isPersian) "۳۰ روز" else "30d",
                                            persianDigits
                                        )
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.sm))

                    val visiblePoints = remember(state.usagePoints, chartRangeDays) {
                        state.usagePoints.takeLast(chartRangeDays)
                    }
                    UsageHistoryChart(
                        points = visiblePoints,
                        emptyText = stringResource(R.string.sub_usage_empty)
                    )

                    Spacer(Modifier.height(Spacing.md))
                    HorizontalDivider(color = colors.glassBorder)
                    Spacer(Modifier.height(Spacing.sm))

                    // Forecast metrics
                    val forecast = state.forecast
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sub_avg_daily),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = Formatters.formatBytes(forecast.averageDailyBytes, persianDigits),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sub_peak_day),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = if (forecast.peakDayBytes > 0L) {
                                    "${forecast.peakDayLabel.orEmpty()} • ${Formatters.formatBytes(forecast.peakDayBytes, persianDigits)}"
                                } else "—",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (forecast.estimatedDaysRemaining != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        val tint = if (forecast.willRunOutBeforeExpiry) colors.statusWarning else colors.statusConnected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(tint.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (forecast.willRunOutBeforeExpiry) Icons.Rounded.WarningAmber else Icons.Rounded.TrendingUp,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = if (forecast.willRunOutBeforeExpiry) {
                                    stringResource(
                                        R.string.sub_forecast_runout_warning,
                                        Formatters.formatNumber(forecast.estimatedDaysRemaining.toLong(), persianDigits)
                                    )
                                } else {
                                    stringResource(
                                        R.string.sub_forecast_days,
                                        Formatters.formatNumber(forecast.estimatedDaysRemaining.toLong(), persianDigits)
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = tint
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                // Subscription Link Tools Card
                if (!sub?.subscriptionUrl.isNullOrBlank()) {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                        Text(
                            text = stringResource(R.string.sub_link_tools_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.sub_link_private_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.statusWarning
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Subscription", sub!!.subscriptionUrl))
                                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_copy), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, sub!!.subscriptionUrl)
                                    }
                                    context.startActivity(Intent.createChooser(intent, null))
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_share), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { showQrSheet = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("QR", maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                // Account Actions & History Card
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                    Text(
                        text = stringResource(R.string.sub_account_actions_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        ActionTileButton(
                            icon = Icons.Rounded.Devices,
                            label = stringResource(R.string.sub_manage_devices),
                            onClick = {
                                viewModel.loadDevices()
                                showDevicesSheet = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ActionTileButton(
                            icon = Icons.Rounded.ReceiptLong,
                            label = if (state.pendingOrderCount > 0) {
                                "${stringResource(R.string.orders_title)} (${Formatters.formatNumber(state.pendingOrderCount.toLong(), persianDigits)})"
                            } else {
                                stringResource(R.string.orders_title)
                            },
                            onClick = onOpenOrders,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        ActionTileButton(
                            icon = Icons.Rounded.Key,
                            label = stringResource(R.string.sub_request_new_link),
                            onClick = { confirmNewLink = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { showSupportSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.sub_contact_support))
                    }
                }

                if (BuildConfig.BILLING_BASE_URL.isNotBlank()) {
                    OutlinedButton(
                        onClick = { Formatters.openExternalUrl(context, BuildConfig.BILLING_BASE_URL.trimEnd('/') + "/account") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.customer_portal)) }
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

    // Subscription QR Modal Bottom Sheet
    if (showQrSheet && !sub?.subscriptionUrl.isNullOrBlank()) {
        val qrBmp = remember(sub!!.subscriptionUrl) {
            QrCodeGenerator.generateBitmap(sub.subscriptionUrl, 520)
        }
        ModalBottomSheet(onDismissRequest = { showQrSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.sub_qr_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.sub_link_private_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.statusWarning,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.md))
                if (qrBmp != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White)
                            .padding(14.dp)
                    ) {
                        Image(
                            bitmap = qrBmp,
                            contentDescription = "Subscription QR",
                            modifier = Modifier.size(220.dp)
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }

    // Devices (HWID) Modal Bottom Sheet
    if (showDevicesSheet) {
        ModalBottomSheet(onDismissRequest = { showDevicesSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .padding(bottom = Spacing.xl)
            ) {
                Text(
                    text = stringResource(R.string.sub_manage_devices),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sub_devices_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(Spacing.md))
                if (state.isLoadingDevices) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.heroEnd)
                    }
                } else {
                    state.devices.forEach { dev ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            cornerRadius = 18.dp,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dev.name + if (dev.isCurrentDevice) {
                                            " (${stringResource(R.string.sub_this_device)})"
                                        } else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "${dev.platform} • ${Formatters.formatRelativeSync(dev.lastSeenEpochMs, persianDigits)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                }
                                if (!dev.isCurrentDevice) {
                                    IconButton(onClick = { viewModel.removeDevice(dev.id, isPersian) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = colors.statusDanger
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Support & Ticket Sheet
    if (showSupportSheet) {
        var subject by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { showSupportSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.sub_contact_support),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = {
                            Formatters.openExternalUrl(
                                context,
                                sub?.supportUrl ?: BrandConfig.supportTelegramUrl
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_telegram))
                    }
                    OutlinedButton(
                        onClick = {
                            Formatters.openExternalUrl(context, BrandConfig.supportWhatsappUrl)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_whatsapp))
                    }
                }
                HorizontalDivider(color = colors.glassBorder)
                Text(
                    text = stringResource(R.string.sub_ticket_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text(stringResource(R.string.sub_ticket_subject)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.sub_ticket_message)) },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                GradientButton(
                    text = stringResource(R.string.sub_ticket_send),
                    enabled = subject.isNotBlank() && body.isNotBlank(),
                    onClick = {
                        viewModel.submitSupportTicket(subject.trim(), body.trim(), isPersian)
                        showSupportSheet = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (confirmNewLink) {
        AlertDialog(
            onDismissRequest = { confirmNewLink = false },
            title = { Text(stringResource(R.string.sub_request_new_link)) },
            text = { Text(stringResource(R.string.sub_confirm_new_link_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.requestNewLink(isPersian)
                    confirmNewLink = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewLink = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun formatLiveCountdown(
    sub: SubscriptionInfo?,
    nowEpochSec: Long,
    isPersian: Boolean,
    persianDigits: Boolean
): String {
    if (sub == null) return "—"
    val secs = sub.remainingSeconds(nowEpochSec) ?: return if (isPersian) "زمان نامحدود" else "Never expires"
    if (secs <= 0L) return if (isPersian) "منقضی شده" else "Expired"
    val days = secs / 86_400L
    val hours = (secs % 86_400L) / 3_600L
    val mins = (secs % 3_600L) / 60L
    val raw = if (isPersian) {
        "$days روز و $hours ساعت و $mins دقیقه باقی‌مانده"
    } else {
        "${days}d ${hours}h ${mins}m remaining"
    }
    return Formatters.toPersianDigitsIfNeeded(raw, persianDigits)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionTileButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(12.dp),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = colors.heroEnd, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
