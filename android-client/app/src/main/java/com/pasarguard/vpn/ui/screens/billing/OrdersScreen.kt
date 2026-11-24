package com.pasarguard.vpn.ui.screens.billing

import com.pasarguard.vpn.ui.components.PasarSheetHandle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.data.api.CustomerApi
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentOrder
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.LocalCalendarSystem
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.util.InvoiceGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class OrdersUiState(
    val orders: List<PaymentOrder> = emptyList(),
    val language: AppLanguage = AppLanguage.PERSIAN
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val customer: CustomerApi,
    settingsRepository: SettingsRepository
) : ViewModel() {

    suspend fun refresh(id: String) = paymentRepository.refreshOrderStatus(id)

    val signedIn: Boolean get() = customer.signedIn
    suspend fun transfer(orderId: String) = customer.transfer(orderId)

    val uiState: StateFlow<OrdersUiState> = combine(
        paymentRepository.observeOrders(),
        settingsRepository.settings
    ) { orders, settings ->
        OrdersUiState(orders = orders, language = settings.language)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrdersUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
    onOpenPlans: () -> Unit,
    onResumeOrder: (String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persianDigits = LocalPersianDigits.current
    val calendarSystem = LocalCalendarSystem.current
    val isPersian = state.language == AppLanguage.PERSIAN
    val locale = if (isPersian) Locale("fa") else Locale.US
    val currency = stringResource(R.string.currency_toman)

    var statusFilter by remember { mutableStateOf<OrderStatus?>(null) }
    var selectedOrder by remember { mutableStateOf<PaymentOrder?>(null) }

    val filtered = remember(state.orders, statusFilter) {
        when (statusFilter) {
            null -> state.orders
            OrderStatus.PENDING_REVIEW -> state.orders.filter {
                it.status == OrderStatus.PENDING_REVIEW || it.status == OrderStatus.AWAITING_RECEIPT
            }
            else -> state.orders.filter { it.status == statusFilter }
        }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = colors.textPrimary
                    )
                }
                Text(
                    text = stringResource(R.string.orders_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
            }

            // Status filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { statusFilter = null },
                    label = { Text(stringResource(R.string.servers_filter_all)) }
                )
                FilterChip(
                    selected = statusFilter == OrderStatus.PENDING_REVIEW,
                    onClick = { statusFilter = OrderStatus.PENDING_REVIEW },
                    label = { Text(stringResource(R.string.order_status_pending)) }
                )
                FilterChip(
                    selected = statusFilter == OrderStatus.APPROVED,
                    onClick = { statusFilter = OrderStatus.APPROVED },
                    label = { Text(stringResource(R.string.order_status_approved)) }
                )
                FilterChip(
                    selected = statusFilter == OrderStatus.REJECTED,
                    onClick = { statusFilter = OrderStatus.REJECTED },
                    label = { Text(stringResource(R.string.order_status_rejected)) }
                )
                FilterChip(
                    selected = statusFilter == OrderStatus.EXPIRED,
                    onClick = { statusFilter = OrderStatus.EXPIRED },
                    label = { Text(stringResource(R.string.order_status_expired)) }
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            if (filtered.isEmpty()) {
                EmptyStateView(
                    title = stringResource(R.string.orders_empty_title),
                    description = stringResource(R.string.orders_empty_desc),
                    actionLabel = stringResource(R.string.plans_title),
                    onAction = onOpenPlans
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(filtered, key = { it.id }) { order ->
                        OrderRowCard(
                            order = order,
                            isPersian = isPersian,
                            persianDigits = persianDigits,
                            dateLabel = Formatters.formatEpochDate(
                                order.createdAtMs / 1000L,
                                locale,
                                calendarSystem,
                                persianDigits
                            ),
                            currency = currency,
                            onClick = { selectedOrder = order }
                        )
                    }
                }
            }
        }
    }

    selectedOrder?.let { order ->
        var updatedAt by remember(order.id) { mutableStateOf<Long?>(null) }
        var stale by remember(order.id) { mutableStateOf(false) }
        LaunchedEffect(order.id) {
            do {
                val latest = viewModel.refresh(order.id).getOrNull()
                stale = latest == null
                if (latest != null) {
                    selectedOrder = latest
                    updatedAt = System.currentTimeMillis()
                }
                val current = latest ?: selectedOrder ?: break
                if (latest != null && current.status.isTerminal && current.activationStatus != "PENDING") break
                kotlinx.coroutines.delay(8_000)
            } while (true)
        }
        ModalBottomSheet(dragHandle = { PasarSheetHandle() },onDismissRequest = { selectedOrder = null }) {
            if (order.newSubscription && order.activationStatus == "READY") {
                var transferring by remember(order.id) { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        if (!viewModel.signedIn) {
                            onOpenAccount()
                            return@OutlinedButton
                        }
                        transferring = true
                        scope.launch {
                            try {
                                viewModel.transfer(order.id)
                                Toast.makeText(context, R.string.account_transfer_success, Toast.LENGTH_SHORT).show()
                                selectedOrder = null
                                onOpenAccount()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.order_transfer_unavailable, Toast.LENGTH_SHORT).show()
                            } finally {
                                transferring = false
                            }
                        }
                    },
                    enabled = !transferring,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)
                ) { Text(stringResource(R.string.order_transfer_to_account)) }
            }
            updatedAt?.let {
                Text(
                    stringResource(R.string.order_last_checked, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(it))),
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }
            if (stale) Text(stringResource(R.string.order_status_stale), modifier = Modifier.padding(horizontal = Spacing.md))
            OrderDetailSheetContent(
                order = order,
                isPersian = isPersian,
                persianDigits = persianDigits,
                dateLabel = Formatters.formatEpochDate(
                    order.createdAtMs / 1000L,
                    locale,
                    calendarSystem,
                    persianDigits
                ),
                currency = currency,
                onCopyOrderId = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Order ID", order.id))
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                },
                onResume = {
                    selectedOrder = null
                    onResumeOrder(order.id)
                },
                onShareInvoice = {
                    scope.launch {
                        InvoiceGenerator.generateInvoiceFile(
                            context = context,
                            order = order,
                            isPersian = isPersian,
                            persianDigits = persianDigits,
                            calendarSystem = calendarSystem
                        ).onSuccess { file ->
                            InvoiceGenerator.shareInvoiceFile(context, file)
                        }.onFailure {
                            Toast.makeText(context, R.string.orders_invoice_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun OrderRowCard(
    order: PaymentOrder,
    isPersian: Boolean,
    persianDigits: Boolean,
    dateLabel: String,
    currency: String,
    onClick: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isPersian) order.planTitleFa else order.planTitleEn,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            OrderStatusChip(status = order.status)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${order.id} • $dateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            Text(
                text = "${Formatters.formatNumber(order.finalPriceToman, persianDigits)} $currency",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.heroEnd
            )
        }
        if (!order.trackingNumber.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${stringResource(R.string.checkout_tracking_number)}: ${Formatters.toPersianDigitsIfNeeded(order.trackingNumber, persianDigits)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun OrderDetailSheetContent(
    order: PaymentOrder,
    isPersian: Boolean,
    persianDigits: Boolean,
    dateLabel: String,
    currency: String,
    onCopyOrderId: () -> Unit,
    onResume: () -> Unit,
    onShareInvoice: () -> Unit
) {
    val colors = PasarGuardTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPersian) order.planTitleFa else order.planTitleEn,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = order.id,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textSecondary
                    )
                    IconButton(onClick = onCopyOrderId, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = colors.heroEnd,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            OrderStatusChip(status = order.status)
        }

        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = PaddingValues(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.checkout_final_price), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Text(
                    "${Formatters.formatNumber(order.finalPriceToman, persianDigits)} $currency",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.heroEnd
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.orders_date_label), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Text(dateLabel, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
            }
            if (!order.trackingNumber.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.checkout_tracking_number), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    Text(
                        Formatters.toPersianDigitsIfNeeded(order.trackingNumber, persianDigits),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textPrimary
                    )
                }
            }
        }

        // Order Timeline
        Text(
            text = stringResource(R.string.orders_timeline_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary
        )
        OrderProgressTimeline(order, isPersian)

        HorizontalDivider(color = colors.glassBorder)

        if (!order.status.isTerminal || order.status == OrderStatus.APPROVED && (order.newSubscription && order.activationStatus == "READY" || !order.hasReceiptImage)) {
            GradientButton(
                text = if (order.status == OrderStatus.REJECTED) {
                    stringResource(R.string.checkout_retry_receipt)
                } else if (order.newSubscription && order.activationStatus == "READY") {
                    stringResource(R.string.purchase_activate)
                } else if (order.status == OrderStatus.APPROVED && !order.hasReceiptImage) {
                    stringResource(R.string.checkout_upload_receipt)
                } else {
                    stringResource(R.string.plans_resume_cta)
                },
                onClick = onResume,
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedButton(onClick = onShareInvoice, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.orders_share_invoice))
        }
    }
}

@Composable
private fun OrderStatusChip(status: OrderStatus) {
    val colors = PasarGuardTheme.colors
    val (labelRes, tint) = when (status) {
        OrderStatus.AWAITING_RECEIPT, OrderStatus.PENDING_REVIEW ->
            R.string.order_status_pending to colors.statusWarning
        OrderStatus.APPROVED ->
            R.string.order_status_approved to colors.statusConnected
        OrderStatus.REJECTED ->
            R.string.order_status_rejected to colors.statusDanger
        OrderStatus.EXPIRED ->
            R.string.order_status_expired to colors.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = tint
        )
    }
}


@Composable
internal fun OrderProgressTimeline(order: PaymentOrder, isPersian: Boolean) {
    val colors = PasarGuardTheme.colors
    val calendar = LocalCalendarSystem.current
    val digits = LocalPersianDigits.current
    val locale = if (isPersian) Locale("fa") else Locale.US
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.buildTimeline().forEach { ev ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (ev.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (ev.completed) colors.statusConnected else colors.statusWarning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = (if (isPersian) ev.titleFa else ev.titleEn) +
                        (ev.timestampMs?.let {
                            val date = Formatters.formatEpochDate(it / 1000L, locale, calendar, digits)
                            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale)
                                .format(java.util.Date(it))
                            " · $date $time"
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textPrimary
                )
            }
        }


        if (order.activationStatus == "PENDING" &&
            System.currentTimeMillis() - (order.reviewedAtMs ?: order.createdAtMs) >= 300_000) {
            Text(stringResource(R.string.activation_delayed), color = colors.statusWarning)
        }
    }
}
