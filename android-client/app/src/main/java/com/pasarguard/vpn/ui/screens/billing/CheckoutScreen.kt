package com.pasarguard.vpn.ui.screens.billing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentOrder
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.util.ReceiptImageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CheckoutScreen(
    planId: String?,
    orderId: String?,
    onBack: () -> Unit,
    onOpenOrders: () -> Unit,
    onDoneHome: () -> Unit,
    onViewSubscription: () -> Unit,
    viewModel: CheckoutViewModel = hiltViewModel()
) {
    LaunchedEffect(planId, orderId) {
        viewModel.init(planId, orderId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadWallet() }
    var pickingPhoto by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.purchaseConnected, state.selectedReceiptPath, state.isPreparingReceipt, state.isSubmittingReceipt, pickingPhoto) {
        if (state.purchaseConnected && state.selectedReceiptPath == null &&
            !state.isPreparingReceipt && !state.isSubmittingReceipt && !pickingPhoto) {
            onDoneHome()
        }
    }
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val persianDigits = LocalPersianDigits.current
    val isPersian = state.language == AppLanguage.PERSIAN
    val currency = stringResource(R.string.currency_toman)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.chooseReceipt(uri)
        pickingPhoto = false
    }
    val receiptContent: @Composable () -> Unit = {
        ReceiptAttachment(
            state, {
                pickingPhoto = true
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, viewModel::removeReceipt,
            onSubmit = if (state.step == CheckoutStep.CARD_TO_CARD) null else viewModel::submitReceipt
        )
    }



    fun copyText(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            // Top bar
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
                    text = when (state.step) {
                        CheckoutStep.SUMMARY -> stringResource(R.string.checkout_summary_title)
                        CheckoutStep.CARD_TO_CARD -> stringResource(R.string.checkout_card_title)
                        CheckoutStep.WAITING -> stringResource(R.string.checkout_waiting_title)
                        CheckoutStep.SUCCESS -> stringResource(R.string.checkout_success_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
            }

            if (!state.isLoading && (state.plan != null || state.order != null)) {
                CheckoutStageProgress(state.step, persianDigits)
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.heroEnd)
                }
            } else {
                if (state.step == CheckoutStep.SUMMARY && state.plan == null && state.errorMessage != null) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.checkout_unavailable), color = colors.statusDanger)
                        TextButton(onClick = { viewModel.retryLoad(planId, orderId) }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                } else when (state.step) {
                    CheckoutStep.SUMMARY -> SummaryAndMethodStep(
                        state = state,
                        isPersian = isPersian,
                        persianDigits = persianDigits,
                        currency = currency,
                        onContinue = viewModel::proceedToCardToCard,
                        onWallet = viewModel::payWithWallet
                    )

                    CheckoutStep.CARD_TO_CARD -> {
                        val order = state.order
                        if (order != null) {
                            CardToCardStep(
                                state = state,
                                order = order,
                                isPersian = isPersian,
                                persianDigits = persianDigits,
                                currency = currency,
                                onCopyCard = { copyText("Card", order.destinationCardNumber) },
                                onCopyAmount = { copyText("Amount", order.finalPriceToman.toString()) },
                                onCardLast4Change = viewModel::updatePayerCardLast4,
                                onTrackingChange = viewModel::updateTrackingNumber,
                                receiptContent = receiptContent,
                                onSubmit = viewModel::submitReceipt,
                                onExpired = viewModel::checkOrderStatusNow
                            )
                        }
                    }

                    CheckoutStep.WAITING -> {
                        val order = state.order
                        if (order != null) {
                            WaitingConfirmationStep(
                                order = order,
                                isPersian = isPersian,
                                onCheckNow = viewModel::checkOrderStatusNow,
                                onStartNewOrder = onBack,
                                supportUrl = BrandConfig.supportTelegramUrl.ifBlank { BrandConfig.supportWhatsappUrl },
                                onCopyOrder = { copyText("Order ID", order.id) },
                                onTrackInOrders = onOpenOrders,
                                receiptContent = receiptContent
                            )
                        }
                    }

                    CheckoutStep.SUCCESS -> {
                        SuccessCelebrationStep(
                            order = state.order,
                            isRefreshingSub = state.isRefreshingSubscription,
                            isPersian = isPersian,
                            purchaseImportFailed = state.purchaseImportFailed,
                            needsActivation = state.order?.newSubscription == true && !state.purchaseConnected,
                            receiptContent = receiptContent,
                            onActivatePurchase = viewModel::activatePurchase,
                            onDoneHome = if (state.order?.newSubscription == false) onViewSubscription else onDoneHome,
                            onViewOrders = onOpenOrders
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckoutStageProgress(step: CheckoutStep, persianDigits: Boolean) {
    val index = when (step) {
        CheckoutStep.SUMMARY -> 1
        CheckoutStep.CARD_TO_CARD -> 2
        CheckoutStep.WAITING -> 3
        CheckoutStep.SUCCESS -> 4
    }
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.checkout_stage_number, Formatters.formatNumber(index.toLong(), persianDigits),
                Formatters.formatNumber(4L, persianDigits)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(progress = { index / 4f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SummaryAndMethodStep(
    state: CheckoutUiState,
    isPersian: Boolean,
    persianDigits: Boolean,
    currency: String,
    onContinue: () -> Unit,
    onWallet: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val plan = state.plan ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Order summary card
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
            Text(
                text = if (isPersian) plan.titleFa else plan.titleEn,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary
            )
            Spacer(Modifier.height(Spacing.sm))
            PriceLineRow(
                label = stringResource(R.string.checkout_base_price),
                value = "${Formatters.formatNumber(plan.priceToman, persianDigits)} $currency"
            )
            state.walletBalance?.let { balance ->
                PriceLineRow(
                    label = stringResource(R.string.account_wallet),
                    value = "${Formatters.formatNumber(balance, persianDigits)} $currency"
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.checkout_card_amount_reservation),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }

        // Payment Method Selector
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Text(
                text = stringResource(R.string.checkout_method_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary
            )
            Spacer(Modifier.height(Spacing.sm))
            PaymentMethodRow(
                icon = Icons.Rounded.CreditCard,
                title = stringResource(R.string.checkout_method_card),
                subtitle = stringResource(R.string.checkout_method_card_desc),
                selected = true,
                enabled = true
            )

        }

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = colors.statusDanger
            )
        }

        GradientButton(
            text = stringResource(R.string.checkout_continue_card),
            onClick = onContinue,
            isLoading = state.isCreatingOrder,
            leadingIcon = Icons.Rounded.CreditCard,
            modifier = Modifier.fillMaxWidth()
        )
        if ((state.walletBalance ?: 0L) >= plan.priceToman) {
            OutlinedButton(onClick = onWallet, enabled = !state.isCreatingOrder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_wallet_pay))
            }
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun CardToCardStep(
    state: CheckoutUiState,
    order: PaymentOrder,
    isPersian: Boolean,
    persianDigits: Boolean,
    currency: String,
    onCopyCard: () -> Unit,
    onCopyAmount: () -> Unit,
    onCardLast4Change: (String) -> Unit,
    onTrackingChange: (String) -> Unit,
    receiptContent: @Composable () -> Unit,
    onSubmit: () -> Unit,
    onExpired: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    var remainingMs by remember(order.id, order.expiresAtMs) {
        mutableLongStateOf(order.remainingPaymentWindowMs())
    }
    LaunchedEffect(order.id, order.expiresAtMs) {
        while (true) {
            val left = order.remainingPaymentWindowMs()
            remainingMs = left
            if (left <= 0L) {
                onExpired()
                break
            }
            delay(1_000L)
        }
    }

    val timerColor = when {
        remainingMs <= 120_000L -> colors.statusDanger
        remainingMs <= 300_000L -> colors.statusWarning
        else -> colors.heroEnd
    }
    val mins = (remainingMs / 60_000L).toInt()
    val secs = ((remainingMs % 60_000L) / 1_000L).toInt()
    val timerText = Formatters.toPersianDigitsIfNeeded(
        String.format("%02d:%02d", mins, secs),
        persianDigits
    )

    val formattedCard = order.destinationCardNumber.filter(Char::isDigit).chunked(4).joinToString("  ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Countdown timer pill
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.2.dp, timerColor.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, contentDescription = null, tint = timerColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.checkout_window_remaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary
                    )
                }
                Text(
                    text = timerText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier.weight(1f),
                    color = timerColor
                )
            }
        }

        // Destination Card & Exact Amount Card
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Text(
                text = if (isPersian) order.destinationBankFa else order.destinationBankEn,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isPersian) order.destinationCardHolderFa else order.destinationCardHolderEn,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))

            // Copyable Card Number
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = Formatters.toPersianDigitsIfNeeded(formattedCard, persianDigits),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onCopyCard) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            // Copyable Exact Amount
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.checkout_exact_amount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${Formatters.formatNumber(order.finalPriceToman, persianDigits)} $currency",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onCopyAmount) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.checkout_do_not_round_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Step-by-step instructions
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
            Text(
                text = stringResource(R.string.checkout_steps_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.checkout_step_1),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Text(
                text = stringResource(R.string.checkout_step_2),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Text(
                text = stringResource(R.string.checkout_step_3),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }

        // Payer Receipt Form
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
            Text(
                text = stringResource(R.string.checkout_payer_form_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = state.payerCardLast4,
                onValueChange = onCardLast4Change,
                label = { Text(stringResource(R.string.checkout_payer_last4)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = state.trackingNumber,
                onValueChange = onTrackingChange,
                label = { Text(stringResource(R.string.checkout_tracking_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.md))
            receiptContent()

            Text(
                text = if (state.language == AppLanguage.PERSIAN)
                    "پس از واریز، عکس فیش یا شمارهٔ پیگیری را برای بررسی ارسال کنید."
                else "After transferring, submit a receipt photo or the tracking number for review.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = colors.statusDanger
            )
        }

        GradientButton(
            text = stringResource(R.string.checkout_submit_receipt_cta),
            onClick = onSubmit,
            isLoading = state.isSubmittingReceipt,
            enabled = remainingMs > 0L && !state.isPreparingReceipt,
            leadingIcon = Icons.Rounded.CheckCircle,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun ReceiptAttachment(
    state: CheckoutUiState,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onSubmit: (() -> Unit)?
) {
    val order = state.order ?: return
    val colors = PasarGuardTheme.colors
    if (order.hasReceiptImage) {
        Text(stringResource(R.string.checkout_receipt_image_saved), color = colors.statusConnected)
        return
    }
    val path = state.selectedReceiptPath
    val preview = remember(path) { path?.let { ReceiptImageUtils.decodePreviewBitmap(it)?.asImageBitmap() } }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = stringResource(R.string.checkout_upload_receipt),
                modifier = Modifier.fillMaxWidth().heightIn(max = 156.dp)
            )
        }
        if (path != null) {
            Text(stringResource(R.string.checkout_receipt_attached), color = colors.textSecondary)
            TextButton(onClick = onRemove, enabled = !state.isSubmittingReceipt && !state.isPreparingReceipt) {
                Text(stringResource(R.string.checkout_remove_receipt))
            }
        }
        OutlinedButton(
            onClick = onPick,
            enabled = !state.isPreparingReceipt && !state.isSubmittingReceipt,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Image, contentDescription = null)
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(if (state.isPreparingReceipt) R.string.checkout_receipt_preparing else R.string.checkout_upload_receipt))
        }
        if (state.receiptImageError) {
            Text(stringResource(R.string.checkout_receipt_image_problem), color = colors.statusDanger)
        }
        if (onSubmit != null && path != null) {
            GradientButton(
                text = stringResource(R.string.checkout_submit_receipt_cta),
                onClick = onSubmit,
                isLoading = state.isSubmittingReceipt,
                enabled = !state.isPreparingReceipt,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WaitingConfirmationStep(
    order: PaymentOrder,
    isPersian: Boolean,
    onCheckNow: () -> Unit,
    onStartNewOrder: () -> Unit,
    supportUrl: String,
    onCopyOrder: () -> Unit,
    onTrackInOrders: () -> Unit,
    receiptContent: @Composable () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val infinite = rememberInfiniteTransition(label = "waiting_pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "pulse_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (order.status) {
            OrderStatus.PENDING_REVIEW -> {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(pulse)
                        .clip(CircleShape)
                        .background(colors.statusWarning.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HourglassTop,
                        contentDescription = null,
                        tint = colors.statusWarning,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = stringResource(R.string.checkout_pending_heading),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.checkout_pending_desc, order.id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.lg))
                GradientButton(
                    text = stringResource(R.string.renew_check_again),
                    onClick = onCheckNow,
                    leadingIcon = Icons.Rounded.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = onTrackInOrders, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.checkout_leave_and_track))
                }
                Spacer(Modifier.height(Spacing.md))
                receiptContent()
            }

            OrderStatus.REJECTED -> {
                Icon(
                    imageVector = Icons.Rounded.Cancel,
                    contentDescription = null,
                    tint = colors.statusDanger,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.checkout_rejected_heading),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.statusDanger,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = (if (isPersian) order.rejectionReasonFa else order.rejectionReasonEn ?: order.rejectionReasonFa)
                        ?: stringResource(R.string.checkout_rejected_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.lg))
                GradientButton(
                    text = stringResource(R.string.checkout_start_new_order),
                    onClick = onStartNewOrder,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OrderStatus.EXPIRED -> {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint = colors.statusDanger,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.checkout_expired_heading),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.statusDanger,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.checkout_expired_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.lg))
                GradientButton(
                    text = stringResource(R.string.checkout_start_new_order),
                    onClick = onStartNewOrder,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> Unit
        }

        Spacer(Modifier.height(Spacing.md))
        OrderProgressTimeline(order, isPersian)
        Spacer(Modifier.height(Spacing.sm))
        TextButton(onClick = onCopyOrder) { Text(stringResource(R.string.orders_copy_id)) }
        if (supportUrl.isNotBlank()) {
            val context = LocalContext.current
            TextButton(onClick = { Formatters.openExternalUrl(context, supportUrl) }) {
                Icon(Icons.Rounded.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sub_contact_support))
            }
        }
    }
}

@Composable
private fun SuccessCelebrationStep(
    order: PaymentOrder?,
    isRefreshingSub: Boolean,
    isPersian: Boolean,
    purchaseImportFailed: Boolean,
    needsActivation: Boolean,
    receiptContent: @Composable () -> Unit,
    onActivatePurchase: () -> Unit,
    onDoneHome: () -> Unit,
    onViewOrders: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    val awaitingApplication = order?.activationStatus == "PENDING"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(colors.statusConnected.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colors.statusConnected,
                modifier = Modifier.size(64.dp)
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(when {
                awaitingApplication -> R.string.renew_payment_approved
                order?.newSubscription == true -> R.string.renew_payment_approved
                else -> R.string.renew_success_title
            }),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (order != null) {
                "${if (isPersian) order.planTitleFa else order.planTitleEn} (${order.id})"
            } else {
                stringResource(R.string.renew_success_desc)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (awaitingApplication || isRefreshingSub || needsActivation && !purchaseImportFailed) {
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.heroEnd)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(when {
                        needsActivation -> R.string.purchase_preparing
                        awaitingApplication -> R.string.renew_applying
                        else -> R.string.checkout_syncing_sub
                    }),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.heroEnd
                )
            }
        }
        if (purchaseImportFailed) {
            Text(stringResource(R.string.purchase_activation_retry), color = colors.statusDanger, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(Spacing.xl))
        order?.let { OrderProgressTimeline(it, isPersian) }
        Spacer(Modifier.height(Spacing.md))
        receiptContent()
        Spacer(Modifier.height(Spacing.md))
        GradientButton(
            text = stringResource(when {
                awaitingApplication -> R.string.renew_applying
                needsActivation -> R.string.purchase_activate
                order?.newSubscription == false -> R.string.checkout_view_subscription
                else -> R.string.renew_done
            }),
            onClick = if (needsActivation) onActivatePurchase else onDoneHome,
            enabled = !awaitingApplication && (!needsActivation || !isRefreshingSub),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onViewOrders, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.orders_view_receipt))
        }
    }
}

@Composable
private fun PriceLineRow(
    label: String,
    value: String,
    valueColor: Color = PasarGuardTheme.colors.textPrimary,
    bold: Boolean = false
) {
    val colors = PasarGuardTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodySmall,
            color = if (bold) colors.textPrimary else colors.textSecondary
        )
        Text(
            text = value,
            style = if (bold) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor
        )
    }
}

@Composable
private fun PaymentMethodRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean
) {
    val colors = PasarGuardTheme.colors
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) colors.heroEnd.copy(alpha = 0.14f) else colors.glassSurface.copy(alpha = 0.5f)
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.heroEnd else colors.glassBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.heroEnd else colors.textSecondary.copy(alpha = alpha),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary.copy(alpha = alpha)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary.copy(alpha = alpha)
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colors.heroEnd,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
