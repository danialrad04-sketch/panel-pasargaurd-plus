package com.pasarguard.vpn.ui.screens.billing

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.data.api.CustomerApi
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.CatalogPlan
import com.pasarguard.vpn.domain.model.DiscountValidationResult
import com.pasarguard.vpn.domain.model.OrderStateMachine
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentMethodType
import com.pasarguard.vpn.domain.model.PaymentOrder
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.usecase.SyncSubscriptionUseCase
import com.pasarguard.vpn.domain.usecase.ValidateAndLoginUseCase
import com.pasarguard.vpn.util.ReceiptImageUtils
import com.pasarguard.vpn.worker.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.File
import javax.inject.Inject

enum class CheckoutStep {
    SUMMARY,
    CARD_TO_CARD,
    WAITING,
    SUCCESS
}

data class CheckoutUiState(
    val isLoading: Boolean = true,
    val step: CheckoutStep = CheckoutStep.SUMMARY,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val plan: CatalogPlan? = null,
    val paymentMethod: PaymentMethodType = PaymentMethodType.CARD_TO_CARD,
    val walletBalance: Long? = null,
    val discountInput: String = "",
    val isValidatingDiscount: Boolean = false,
    val appliedDiscount: DiscountValidationResult? = null,
    val order: PaymentOrder? = null,
    val isCreatingOrder: Boolean = false,
    val payerCardLast4: String = "",
    val trackingNumber: String = "",
    val isSubmittingReceipt: Boolean = false,
    val selectedReceiptPath: String? = null,
    val isPreparingReceipt: Boolean = false,
    val receiptImageError: Boolean = false,
    val isRefreshingSubscription: Boolean = false,
    val purchaseConnected: Boolean = false,
    val purchaseImportFailed: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val paymentRepository: PaymentRepository,
    private val subscriptions: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val syncSubscription: SyncSubscriptionUseCase,
    private val validateAndLogin: ValidateAndLoginUseCase,
    private val customer: CustomerApi,
    private val notificationHelper: NotificationHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var idempotencyKey: String = UUID.randomUUID().toString()
    private var pollJob: Job? = null
    private var observeOrderJob: Job? = null
    private var initializedKey: String? = null
    private var approvedHandledForOrderId: String? = null
    private var attemptedPurchaseId: String? = null

    fun hasActiveSubscription(): Boolean = subscriptions.getSavedCredentials() != null

    fun init(planId: String?, orderId: String?) {
        val key = "${planId.orEmpty()}|${orderId.orEmpty()}"
        if (initializedKey == key) return
        initializedKey = key

        viewModelScope.launch {
            val lang = settingsRepository.getSnapshot().language
            _uiState.update { it.copy(isLoading = true, language = lang, errorMessage = null) }

            if (!orderId.isNullOrBlank()) {
                val existing = paymentRepository.getOrderById(orderId)
                if (existing != null) {
                    attachOrder(existing)
                    return@launch
                }
                _uiState.update { it.copy(isLoading = false, errorMessage = "Order unavailable") }
                return@launch
            }

            val catalog = paymentRepository.loadPlansCatalog().getOrNull()
            val selectedPlan = catalog?.plans?.firstOrNull { it.id == planId }
                ?: catalog?.plans?.firstOrNull()

            if (selectedPlan == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Plan not found") }
                return@launch
            }

            idempotencyKey = UUID.randomUUID().toString()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    step = CheckoutStep.SUMMARY,
                    plan = selectedPlan
                )
            }
        }
    }

    private fun attachOrder(order: PaymentOrder) {
        val step = when (order.status) {
            OrderStatus.AWAITING_RECEIPT -> CheckoutStep.CARD_TO_CARD
            OrderStatus.PENDING_REVIEW, OrderStatus.REJECTED, OrderStatus.EXPIRED -> CheckoutStep.WAITING
            OrderStatus.APPROVED -> CheckoutStep.SUCCESS
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                order = order,
                step = step,
                payerCardLast4 = order.payerCardLast4.orEmpty(),
                trackingNumber = order.trackingNumber.orEmpty()
            )
        }
        observeOrderUpdates(order.id)
        if (needsPolling(order)) startPollingOrder(order.id)
        if (order.status == OrderStatus.APPROVED) onOrderApproved(order)
    }

    private fun observeOrderUpdates(orderId: String) {
        observeOrderJob?.cancel()
        observeOrderJob = viewModelScope.launch {
            paymentRepository.observeOrderById(orderId).collect { latest ->
                if (latest == null) return@collect
                _uiState.update { cur ->
                    val nextStep = when {
                        latest.status == OrderStatus.APPROVED -> CheckoutStep.SUCCESS
                        cur.step == CheckoutStep.CARD_TO_CARD && latest.status == OrderStatus.EXPIRED -> CheckoutStep.WAITING
                        else -> cur.step
                    }
                    cur.copy(order = latest, step = nextStep)
                }
                if (latest.status == OrderStatus.APPROVED) {
                    if (!needsPolling(latest)) pollJob?.cancel()
                    onOrderApproved(latest)
                } else if (latest.status == OrderStatus.REJECTED || latest.status == OrderStatus.EXPIRED) {
                    pollJob?.cancel()
                }
            }
        }
    }

    fun updateDiscountInput(value: String) {
        _uiState.update { it.copy(discountInput = value, errorMessage = null) }
    }

    fun applyDiscount() {
        val plan = _uiState.value.plan ?: return
        val code = _uiState.value.discountInput.trim()
        if (code.isBlank() || _uiState.value.isValidatingDiscount) return
        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingDiscount = true, errorMessage = null) }
            paymentRepository.validateDiscountCode(plan.id, code)
                .onSuccess { res ->
                    _uiState.update { it.copy(isValidatingDiscount = false, appliedDiscount = res) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isValidatingDiscount = false, errorMessage = err.localizedMessage)
                    }
                }
        }
    }

    fun clearDiscount() {
        _uiState.update { it.copy(discountInput = "", appliedDiscount = null) }
    }

    fun loadWallet() {
        if (!customer.signedIn) return
        viewModelScope.launch {
            runCatching { customer.wallet().balance }.onSuccess { balance ->
                _uiState.update { it.copy(walletBalance = balance) }
            }
        }
    }

    fun payWithWallet() = proceedToCardToCard(PaymentMethodType.WALLET)

    fun proceedToCardToCard() = proceedToCardToCard(PaymentMethodType.CARD_TO_CARD)

    private fun proceedToCardToCard(method: PaymentMethodType) {
        val state = _uiState.value
        val plan = state.plan ?: return
        if (state.isCreatingOrder) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingOrder = true, errorMessage = null) }
            val validCode = state.appliedDiscount?.takeIf { it.isValid }?.code
            paymentRepository.createOrder(
                plan = plan,
                paymentMethod = method,
                discountCode = validCode,
                idempotencyKey = if (method == PaymentMethodType.WALLET) {
                    UUID.nameUUIDFromBytes((idempotencyKey + ":wallet").toByteArray()).toString()
                } else idempotencyKey
            ).onSuccess { order ->
                _uiState.update { it.copy(isCreatingOrder = false) }
                attachOrder(order)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCreatingOrder = false,
                        errorMessage = err.localizedMessage ?: "Failed to create order"
                    )
                }
            }
        }
    }

    fun updatePayerCardLast4(raw: String) {
        val digits = raw.filter(Char::isDigit).take(4)
        _uiState.update { it.copy(payerCardLast4 = digits, errorMessage = null) }
    }

    fun updateTrackingNumber(raw: String) {
        _uiState.update { it.copy(trackingNumber = raw.filter(Char::isDigit).take(64), errorMessage = null) }
    }

    fun chooseReceipt(uri: Uri) {
        val state = _uiState.value
        val order = state.order ?: return
        if (state.isPreparingReceipt || state.isSubmittingReceipt || order.hasReceiptImage) return
        _uiState.update { it.copy(isPreparingReceipt = true, receiptImageError = false, errorMessage = null) }
        viewModelScope.launch {
            val result = ReceiptImageUtils.compressReceiptUri(appContext, uri)
            if (result.isSuccess) {
                _uiState.value.selectedReceiptPath?.let { File(it).delete() }
                _uiState.update { it.copy(selectedReceiptPath = result.getOrThrow().filePath, isPreparingReceipt = false) }
            } else {
                _uiState.update { it.copy(isPreparingReceipt = false, receiptImageError = true) }
            }
        }
    }

    fun removeReceipt() {
        if (_uiState.value.isSubmittingReceipt || _uiState.value.isPreparingReceipt) return
        _uiState.value.selectedReceiptPath?.let { File(it).delete() }
        _uiState.update { it.copy(selectedReceiptPath = null, receiptImageError = false) }
    }

    fun submitReceipt() {
        val state = _uiState.value
        val order = state.order ?: return
        if (state.isSubmittingReceipt || state.isPreparingReceipt) return
        val tracking = state.trackingNumber.trim()
        if (tracking.length in 1..3 || tracking.isEmpty() && state.selectedReceiptPath == null) {
            _uiState.update {
                it.copy(
                    errorMessage = if (state.language == AppLanguage.PERSIAN) {
                        "شمارهٔ پیگیری معتبر یا عکس فیش پرداخت را وارد کنید."
                    } else {
                        "Enter a valid tracking number or attach a payment receipt."
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingReceipt = true, errorMessage = null) }
            paymentRepository.submitCardReceipt(
                orderId = order.id,
                payerCardLast4 = state.payerCardLast4,
                trackingNumber = state.trackingNumber,
                receiptLocalPath = state.selectedReceiptPath
            ).onSuccess { updated ->
                val imageSaved = state.selectedReceiptPath == null || updated.hasReceiptImage
                if (imageSaved) state.selectedReceiptPath?.let { File(it).delete() }
                _uiState.update {
                    it.copy(
                        isSubmittingReceipt = false,
                        selectedReceiptPath = if (imageSaved) null else it.selectedReceiptPath,
                        receiptImageError = !imageSaved,
                        order = updated,
                        step = if (updated.status == OrderStatus.APPROVED) CheckoutStep.SUCCESS else CheckoutStep.WAITING
                    )
                }
                if (updated.status == OrderStatus.APPROVED) onOrderApproved(updated)
                if (needsPolling(updated)) startPollingOrder(updated.id)
            }.onFailure { error ->
                val status = (error as? retrofit2.HttpException)?.code()
                if (status == 409) paymentRepository.refreshOrderStatus(order.id)
                val isPersian = state.language == AppLanguage.PERSIAN
                val message = when (status) {
                    409 -> if (isPersian) "وضعیت سفارش تغییر کرده است. جزئیات سفارش را بررسی کنید."
                        else "The order changed. Check its current status."
                    400, 413 -> if (isPersian) "اطلاعات یا اندازهٔ تصویر رسید را بررسی کنید."
                        else "Check the receipt details or image size."
                    else -> if (isPersian) "ارسال رسید کامل نشد. دوباره تلاش کنید."
                        else "Receipt upload failed. Please retry."
                }
                _uiState.update { it.copy(isSubmittingReceipt = false, receiptImageError = false, errorMessage = message) }
            }
        }
    }

    fun checkOrderStatusNow() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            paymentRepository.refreshOrderStatus(orderId)
        }
    }

    private fun startPollingOrder(orderId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                delay(OrderStateMachine.nextPollDelayMs(attempt))
                val latest = paymentRepository.refreshOrderStatus(orderId).getOrNull()
                if (latest != null && !needsPolling(latest)) break
                attempt = (attempt + 1).coerceAtMost(6)
            }
        }
    }

    private fun needsPolling(order: PaymentOrder): Boolean =
        order.status == OrderStatus.PENDING_REVIEW || order.status == OrderStatus.AWAITING_RECEIPT ||
            order.status == OrderStatus.APPROVED && order.activationStatus == "PENDING"

    private fun onOrderApproved(order: PaymentOrder) {
        if (order.activationStatus == "PENDING") return
        if (approvedHandledForOrderId != order.id) {
            approvedHandledForOrderId = order.id
            val planTitle = if (_uiState.value.language == AppLanguage.PERSIAN) order.planTitleFa else order.planTitleEn
            notificationHelper.showOrderApprovedNotification(order.id, planTitle)
            if (!order.newSubscription) {
                viewModelScope.launch {
                    _uiState.update { it.copy(isRefreshingSubscription = true) }
                    syncSubscription()
                    _uiState.update { it.copy(isRefreshingSubscription = false) }
                }
            }
        }
        if (order.newSubscription && !hasActiveSubscription() &&
            order.activationStatus == "READY" && attemptedPurchaseId != order.id) {
            attemptedPurchaseId = order.id
            activatePurchase()
        }
    }

    fun activatePurchase() {
        val order = _uiState.value.order ?: return
        if (!order.newSubscription || _uiState.value.isRefreshingSubscription) return
        if (order.activationStatus != "READY") {
            checkOrderStatusNow()
            return
        }
        _uiState.update { it.copy(isRefreshingSubscription = true, purchaseImportFailed = false) }
        viewModelScope.launch {
            val link = paymentRepository.getPurchasedSubscription(order.id)
            val connected = link.isSuccess && validateAndLogin(link.getOrThrow(), requireServers = true).isSuccess
            _uiState.update {
                it.copy(isRefreshingSubscription = false, purchaseConnected = connected, purchaseImportFailed = !connected)
            }
        }
    }

    override fun onCleared() {
        _uiState.value.selectedReceiptPath?.let { File(it).delete() }
        pollJob?.cancel()
        observeOrderJob?.cancel()
        super.onCleared()
    }
}
