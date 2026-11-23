package com.pasarguard.vpn.ui.screens.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.CatalogPlan
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentOrder
import com.pasarguard.vpn.domain.model.PlanCategory
import com.pasarguard.vpn.domain.model.UsageForecastCalculator
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlansUiState(
    val isLoading: Boolean = true,
    val plans: List<CatalogPlan> = emptyList(),
    val selectedCategory: PlanCategory = PlanCategory.RENEW,
    val recommendedPlanId: String? = null,
    val activePendingOrder: PaymentOrder? = null,
    val guestCheckout: Boolean = false,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val error: String? = null
)

@HiltViewModel
class PlansViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlansUiState())
    val uiState: StateFlow<PlansUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val settings = settingsRepository.getSnapshot()
            val guestCheckout = subscriptionRepository.getSavedCredentials() == null
            _uiState.update { it.copy(guestCheckout = guestCheckout, language = settings.language) }
            val sub = subscriptionRepository.observeSubscription().firstOrNull()
            val usage = subscriptionRepository.observeUsageHistory().firstOrNull().orEmpty()
            val pending = paymentRepository.getActivePendingOrder()

            paymentRepository.loadPlansCatalog()
                .onSuccess { catalog ->
                    val forecast = UsageForecastCalculator.calculate(sub, usage, catalog.plans)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            plans = catalog.plans,
                            recommendedPlanId = forecast.recommendedPlanId,
                            activePendingOrder = pending,
                            language = settings.language
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            language = settings.language,
                            error = err.localizedMessage ?: "Failed to load plans"
                        )
                    }
                }
        }
    }

    fun selectCategory(category: PlanCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(
    onBack: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onResumeOrder: (String) -> Unit,
    onOpenOrders: () -> Unit,
    viewModel: PlansViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current
    val isPersian = state.language == AppLanguage.PERSIAN
    var showCompareSheet by remember { mutableStateOf(false) }

    val visiblePlans = remember(state.plans, state.selectedCategory) {
        state.plans.filter { it.category == state.selectedCategory }
    }

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            // Top bar
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
                        text = stringResource(if (state.guestCheckout) R.string.purchase_new_subscription else R.string.plans_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (visiblePlans.size >= 2) {
                        IconButton(onClick = { showCompareSheet = true }) {
                            Icon(
                                imageVector = Icons.Rounded.CompareArrows,
                                contentDescription = stringResource(R.string.plans_compare),
                                tint = colors.heroEnd
                            )
                        }
                    }
                    IconButton(onClick = onOpenOrders) {
                        Icon(
                            imageVector = Icons.Rounded.ReceiptLong,
                            contentDescription = stringResource(R.string.orders_title),
                            tint = colors.textPrimary
                        )
                    }
                }
            }

            if (!state.guestCheckout) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CategoryChip(
                    selected = state.selectedCategory == PlanCategory.RENEW,
                    label = stringResource(R.string.plans_cat_renew),
                    onClick = { viewModel.selectCategory(PlanCategory.RENEW) }
                )
                CategoryChip(
                    selected = state.selectedCategory == PlanCategory.UPGRADE,
                    label = stringResource(R.string.plans_cat_upgrade),
                    onClick = { viewModel.selectCategory(PlanCategory.UPGRADE) }
                )
                CategoryChip(
                    selected = state.selectedCategory == PlanCategory.EXTRA_TRAFFIC,
                    label = stringResource(R.string.plans_cat_extra_traffic),
                    onClick = { viewModel.selectCategory(PlanCategory.EXTRA_TRAFFIC) }
                )
                CategoryChip(
                    selected = state.selectedCategory == PlanCategory.EXTRA_DAYS,
                    label = stringResource(R.string.plans_cat_extra_days),
                    onClick = { viewModel.selectCategory(PlanCategory.EXTRA_DAYS) }
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            // Resume pending order banner if app was closed mid-checkout
            state.activePendingOrder?.let { pending ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.2.dp, colors.statusWarning.copy(alpha = 0.65f), RoundedCornerShape(20.dp)),
                    cornerRadius = 20.dp,
                    contentPadding = PaddingValues(14.dp),
                    onClick = { onResumeOrder(pending.id) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PendingActions,
                                contentDescription = null,
                                tint = colors.statusWarning,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Column {
                                Text(
                                    text = stringResource(
                                        if (pending.newSubscription && pending.status == OrderStatus.APPROVED)
                                            R.string.purchase_activate
                                        else R.string.plans_resume_order_title
                                    ),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = colors.statusWarning
                                )
                                Text(
                                    text = "${if (isPersian) pending.planTitleFa else pending.planTitleEn} • ${pending.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        TextButton(onClick = { onResumeOrder(pending.id) }) {
                            Text(stringResource(R.string.plans_resume_cta), color = colors.heroEnd)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.heroEnd)
                    }
                }

                state.error != null -> {
                    EmptyStateView(
                        title = stringResource(R.string.plans_error_title),
                        description = if (state.guestCheckout) stringResource(R.string.purchase_unavailable) else state.error.orEmpty(),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { viewModel.reload() }
                    )
                }

                visiblePlans.isEmpty() -> {
                    EmptyStateView(
                        title = stringResource(R.string.plans_empty_title),
                        description = stringResource(R.string.plans_empty_desc)
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        items(visiblePlans, key = { it.id }) { plan ->
                            PlanCardItem(
                                plan = plan,
                                isRecommended = plan.id == state.recommendedPlanId,
                                isPersian = isPersian,
                                persianDigits = persianDigits,
                                onChoose = { onSelectPlan(plan.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCompareSheet && visiblePlans.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showCompareSheet = false }) {
            ComparePlansSheetContent(
                plans = visiblePlans,
                isPersian = isPersian,
                persianDigits = persianDigits,
                onChoose = { id ->
                    showCompareSheet = false
                    onSelectPlan(id)
                }
            )
        }
    }
}

@Composable
private fun CategoryChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun PlanCardItem(
    plan: CatalogPlan,
    isRecommended: Boolean,
    isPersian: Boolean,
    persianDigits: Boolean,
    onChoose: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val currency = stringResource(R.string.currency_toman)
    val highlighted = plan.isPopular || isRecommended

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier.border(
                        1.6.dp,
                        colors.heroEnd.copy(alpha = 0.75f),
                        RoundedCornerShape(26.dp)
                    )
                } else Modifier
            ),
        cornerRadius = 26.dp,
        onClick = onChoose
    ) {
        // Tag badges row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecommended) {
                TagBadge(
                    text = stringResource(R.string.plans_recommended_for_you),
                    color = colors.accentViolet
                )
            }
            val tag = if (isPersian) plan.tagFa else plan.tagEn
            if (!tag.isNullOrBlank()) {
                TagBadge(
                    text = tag,
                    color = if (plan.isBestValue) colors.statusConnected else colors.heroEnd
                )
            }
            plan.discountPercent?.let { pct ->
                TagBadge(
                    text = Formatters.toPersianDigitsIfNeeded(
                        if (isPersian) "$pct٪ تخفیف" else "-$pct%",
                        persianDigits
                    ),
                    color = colors.statusDanger
                )
            }
        }

        if (isRecommended || !plan.tagFa.isNullOrBlank() || plan.discountPercent != null) {
            Spacer(Modifier.height(Spacing.sm))
        }

        // Title + Price Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPersian) plan.titleFa else plan.titleEn,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                val metaParts = buildList {
                    if (plan.isUnlimitedData) {
                        add(stringResource(R.string.home_unlimited))
                    } else if ((plan.dataLimitGb ?: 0) > 0) {
                        add(
                            Formatters.toPersianDigitsIfNeeded(
                                "${plan.dataLimitGb} GB",
                                persianDigits
                            )
                        )
                    }
                    if (plan.durationDays > 0) {
                        add(
                            Formatters.toPersianDigitsIfNeeded(
                                if (isPersian) "${plan.durationDays} روز" else "${plan.durationDays} days",
                                persianDigits
                            )
                        )
                    }
                    plan.maxDevices?.let { dev ->
                        add(
                            Formatters.toPersianDigitsIfNeeded(
                                if (isPersian) "$dev دستگاه" else "$dev devices",
                                persianDigits
                            )
                        )
                    }
                }
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }

            Spacer(Modifier.width(Spacing.sm))

            Column(horizontalAlignment = Alignment.End) {
                plan.oldPriceToman?.let { old ->
                    Text(
                        text = "${Formatters.formatNumber(old, persianDigits)} $currency",
                        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.LineThrough),
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = "${Formatters.formatNumber(plan.priceToman, persianDigits)} $currency",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.heroEnd
                )
            }
        }

        // Price per GB / per day hints
        val hints = buildList {
            plan.pricePerGbToman?.let { perGb ->
                add(
                    if (isPersian) {
                        "هر گیگ: ${Formatters.formatNumber(perGb, persianDigits)} $currency"
                    } else {
                        "${Formatters.formatNumber(perGb, persianDigits)} $currency / GB"
                    }
                )
            }
            plan.pricePerDayToman?.let { perDay ->
                add(
                    if (isPersian) {
                        "روزانه: ${Formatters.formatNumber(perDay, persianDigits)} $currency"
                    } else {
                        "${Formatters.formatNumber(perDay, persianDigits)} $currency / day"
                    }
                )
            }
        }
        if (hints.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hints.joinToString("  •  "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.statusConnected
            )
        }

        // Feature bullets
        val features = if (isPersian) plan.featuresFa else plan.featuresEn
        if (features.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = colors.glassBorder)
            Spacer(Modifier.height(Spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                features.forEach { feat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.statusConnected,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = feat,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        GradientButton(
            text = stringResource(R.string.plans_choose_cta),
            onClick = onChoose,
            leadingIcon = Icons.Rounded.AutoAwesome,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TagBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
private fun ComparePlansSheetContent(
    plans: List<CatalogPlan>,
    isPersian: Boolean,
    persianDigits: Boolean,
    onChoose: (String) -> Unit
) {
    val colors = PasarGuardTheme.colors
    val currency = stringResource(R.string.currency_toman)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .padding(bottom = Spacing.xl)
    ) {
        Text(
            text = stringResource(R.string.plans_compare),
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            plans.forEach { plan ->
                GlassCard(
                    modifier = Modifier.width(210.dp),
                    cornerRadius = 22.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Text(
                        text = if (isPersian) plan.titleFa else plan.titleEn,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${Formatters.formatNumber(plan.priceToman, persianDigits)} $currency",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.heroEnd
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = colors.glassBorder)
                    Spacer(Modifier.height(8.dp))
                    CompareMetricRow(
                        label = stringResource(R.string.compare_data),
                        value = if (plan.isUnlimitedData) {
                            stringResource(R.string.home_unlimited)
                        } else {
                            Formatters.toPersianDigitsIfNeeded("${plan.dataLimitGb ?: 0} GB", persianDigits)
                        }
                    )
                    CompareMetricRow(
                        label = stringResource(R.string.compare_duration),
                        value = if (plan.durationDays > 0) {
                            Formatters.toPersianDigitsIfNeeded(
                                if (isPersian) "${plan.durationDays} روز" else "${plan.durationDays}d",
                                persianDigits
                            )
                        } else "—"
                    )
                    CompareMetricRow(
                        label = stringResource(R.string.compare_devices),
                        value = plan.maxDevices?.let { Formatters.formatNumber(it.toLong(), persianDigits) } ?: "—"
                    )
                    CompareMetricRow(
                        label = stringResource(R.string.compare_per_gb),
                        value = plan.pricePerGbToman?.let {
                            Formatters.formatNumber(it, persianDigits)
                        } ?: "—"
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { onChoose(plan.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.plans_choose_cta))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareMetricRow(label: String, value: String) {
    val colors = PasarGuardTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary
        )
    }
}
