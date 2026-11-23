package com.pasarguard.vpn.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.data.api.OrderPushEventReceiver
import com.pasarguard.vpn.data.db.OrderDao
import com.pasarguard.vpn.data.db.OrderEntity
import com.pasarguard.vpn.domain.model.AccountActionResult
import com.pasarguard.vpn.domain.model.CatalogPlan
import com.pasarguard.vpn.domain.model.CheckoutSession
import com.pasarguard.vpn.domain.model.ConnectedDevice
import com.pasarguard.vpn.domain.model.DestinationCardInfo
import com.pasarguard.vpn.domain.model.DiscountValidationResult
import com.pasarguard.vpn.domain.model.OrderStateMachine
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentMethodType
import com.pasarguard.vpn.domain.model.PaymentOrder
import com.pasarguard.vpn.domain.model.PlanCategory
import com.pasarguard.vpn.domain.model.PlansCatalogConfig
import com.pasarguard.vpn.domain.model.RenewalPlan
import com.pasarguard.vpn.domain.model.SupportTicketRequest
import com.pasarguard.vpn.domain.repository.AccountActionsRepository
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full-featured simulated implementation of [PaymentRepository], [AccountActionsRepository],
 * and [OrderPushEventReceiver] backed by Room for realistic offline/frontend testing before the
 * billing backend is deployed.
 *
 * - Reads plans and destination card info from `assets/plans_config.json`.
 * - Persists orders in Room so killed-app mid-checkout recovery and Orders History work across restarts.
 * - Enforces idempotency keys and [OrderStateMachine] transitions.
 * - Never embeds or logs admin tokens, credentials, or receipt contents.
 */
@Singleton
class FakePaymentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderDao: OrderDao,
    private val json: Json
) : PaymentRepository, AccountActionsRepository, OrderPushEventReceiver {

    private val mutex = Mutex()
    private val pollAttempts = ConcurrentHashMap<String, Int>()
    private var cachedCatalog: PlansCatalogConfig? = null

    private val fakeDevices = mutableListOf(
        ConnectedDevice(
            id = "dev_current_android",
            name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            platform = "Android ${Build.VERSION.RELEASE}",
            model = Build.MODEL,
            lastSeenEpochMs = System.currentTimeMillis(),
            isCurrentDevice = true
        ),
        ConnectedDevice(
            id = "dev_laptop_01",
            name = "MacBook Air M2",
            platform = "macOS 15",
            model = "Apple Silicon",
            lastSeenEpochMs = System.currentTimeMillis() - 4_200_000L,
            isCurrentDevice = false
        ),
        ConnectedDevice(
            id = "dev_ios_02",
            name = "iPhone 15 Pro",
            platform = "iOS 18",
            model = "iPhone",
            lastSeenEpochMs = System.currentTimeMillis() - 36_000_000L,
            isCurrentDevice = false
        )
    )

    override suspend fun loadPlansCatalog(): Result<PlansCatalogConfig> = withContext(Dispatchers.IO) {
        runCatching {
            cachedCatalog?.let { return@runCatching it }
            val parsed = runCatching {
                context.assets.open("plans_config.json").bufferedReader().use { it.readText() }
            }.mapCatching { raw ->
                json.decodeFromString<PlansCatalogConfig>(raw)
            }.getOrElse { fallbackCatalog() }
            cachedCatalog = parsed
            parsed
        }
    }

    override suspend fun getAvailablePlans(): Result<List<RenewalPlan>> {
        return loadPlansCatalog().map { cfg ->
            cfg.plans
                .filter { it.category == PlanCategory.RENEW }
                .map { p ->
                    RenewalPlan(
                        id = p.id,
                        titleEn = p.titleEn,
                        titleFa = p.titleFa,
                        durationDays = p.durationDays,
                        dataLimitGb = p.dataLimitGb,
                        priceDisplayEn = "${Formatters.formatNumber(p.priceToman, false)} Toman",
                        priceDisplayFa = "${Formatters.formatNumber(p.priceToman, true)} تومان",
                        isPopular = p.isPopular,
                        badgeEn = p.tagEn,
                        badgeFa = p.tagFa
                    )
                }
        }
    }

    override suspend fun createCheckoutSession(
        planId: String,
        subscriptionToken: String,
        callbackUrl: String
    ): Result<CheckoutSession> {
        val refId = "PG-${UUID.randomUUID().toString().take(8).uppercase()}"
        val checkoutUri = Uri.parse("${BrandConfig.defaultBaseUrl}/billing/checkout")
            .buildUpon()
            .appendQueryParameter("plan", planId)
            .appendQueryParameter("ref", refId)
            .appendQueryParameter("return_url", callbackUrl)
            .build()
            .toString()
        return Result.success(CheckoutSession(refId, checkoutUri, planId))
    }

    override suspend fun validateDiscountCode(
        planId: String,
        code: String
    ): Result<DiscountValidationResult> = withContext(Dispatchers.IO) {
        delay(350L)
        val clean = code.trim().uppercase()
        if (clean.isBlank()) {
            return@withContext Result.success(
                DiscountValidationResult(
                    code = "",
                    isValid = false,
                    messageFa = "کد تخفیف را وارد کنید.",
                    messageEn = "Please enter a discount code."
                )
            )
        }
        val catalog = loadPlansCatalog().getOrElse { fallbackCatalog() }
        val plan = catalog.plans.firstOrNull { it.id == planId }
            ?: return@withContext Result.failure(IllegalArgumentException("Plan not found"))

        val result = when (clean) {
            "WELCOME15", "PASAR15" -> {
                val amount = (plan.priceToman * 15L) / 100L
                DiscountValidationResult(
                    code = clean,
                    isValid = true,
                    discountAmountToman = amount,
                    discountPercent = 15,
                    messageFa = "کد تخفیف ۱۵٪ اعمال شد.",
                    messageEn = "15% discount applied."
                )
            }
            "PASAR50" -> {
                val amount = minOf(50_000L, (plan.priceToman * 40L) / 100L)
                DiscountValidationResult(
                    code = clean,
                    isValid = true,
                    discountAmountToman = amount,
                    discountPercent = null,
                    messageFa = "کد تخفیف ۵۰٬۰۰۰ تومانی اعمال شد.",
                    messageEn = "50,000 Toman discount applied."
                )
            }
            "EXPIRED" -> DiscountValidationResult(
                code = clean,
                isValid = false,
                messageFa = "مهلت استفاده از این کد تخفیف به پایان رسیده است.",
                messageEn = "This discount code has expired."
            )
            else -> DiscountValidationResult(
                code = clean,
                isValid = false,
                messageFa = "کد تخفیف نامعتبر است (کدهای آزمایشی: WELCOME15 یا PASAR50).",
                messageEn = "Invalid discount code (demo codes: WELCOME15 or PASAR50)."
            )
        }
        Result.success(result)
    }

    override suspend fun createOrder(
        plan: CatalogPlan,
        paymentMethod: PaymentMethodType,
        discountCode: String?,
        idempotencyKey: String
    ): Result<PaymentOrder> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                // Idempotency guard: never create duplicate orders for the same idempotencyKey
                orderDao.getByIdempotencyKey(idempotencyKey)?.let { existing ->
                    return@runCatching existing.toDomain()
                }

                delay(450L)
                val catalog = loadPlansCatalog().getOrElse { fallbackCatalog() }
                var discountAmt = 0L
                val normalizedCode = discountCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                if (normalizedCode != null) {
                    val check = validateDiscountCode(plan.id, normalizedCode).getOrNull()
                    if (check != null && check.isValid) {
                        discountAmt = check.discountAmountToman
                    }
                }
                val finalAmount = (plan.priceToman - discountAmt).coerceAtLeast(1_000L)
                val now = System.currentTimeMillis()
                val windowMs = catalog.paymentWindowMinutes.coerceIn(5, 120) * 60_000L
                val orderId = "PG-${UUID.randomUUID().toString().take(8).uppercase()}"

                val entity = OrderEntity(
                    id = orderId,
                    idempotencyKey = idempotencyKey,
                    planId = plan.id,
                    planTitleFa = plan.titleFa,
                    planTitleEn = plan.titleEn,
                    planCategory = plan.category.name,
                    paymentMethod = paymentMethod.name,
                    status = OrderStatus.AWAITING_RECEIPT.name,
                    basePriceToman = plan.priceToman,
                    discountCode = if (discountAmt > 0L) normalizedCode else null,
                    discountAmountToman = discountAmt,
                    finalPriceToman = finalAmount,
                    destinationCardNumber = catalog.destinationCard.cardNumber,
                    destinationCardHolderFa = catalog.destinationCard.holderNameFa,
                    destinationCardHolderEn = catalog.destinationCard.holderNameEn,
                    destinationBankFa = catalog.destinationCard.bankNameFa,
                    destinationBankEn = catalog.destinationCard.bankNameEn,
                    payerCardLast4 = null,
                    trackingNumber = null,
                    receiptLocalPath = null,
                    rejectionReasonFa = null,
                    rejectionReasonEn = null,
                    createdAtMs = now,
                    expiresAtMs = now + windowMs,
                    submittedAtMs = null,
                    reviewedAtMs = null
                )
                orderDao.upsert(entity)
                entity.toDomain()
            }
        }
    }

    override suspend fun submitCardReceipt(
        orderId: String,
        payerCardLast4: String,
        trackingNumber: String,
        receiptLocalPath: String?
    ): Result<PaymentOrder> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val existing = orderDao.getById(orderId)?.toDomain()
                    ?: error("Order not found")

                val now = System.currentTimeMillis()
                val effectiveStatus = OrderStateMachine.evaluateEffectiveStatus(existing, now)
                if (effectiveStatus == OrderStatus.EXPIRED) {
                    val expired = existing.copy(status = OrderStatus.EXPIRED)
                    orderDao.upsert(expired.toEntity())
                    error("Payment window expired")
                }
                if (!existing.status.canSubmitReceipt) {
                    return@runCatching existing
                }

                delay(650L)

                val cleanLast4 = payerCardLast4.filter(Char::isDigit).takeLast(4)
                val cleanTracking = trackingNumber.trim()
                require(cleanLast4.length == 4 || cleanTracking.length >= 4) {
                    "Enter last 4 digits of payer card or tracking number"
                }

                val updated = existing.copy(
                    status = OrderStatus.PENDING_REVIEW,
                    payerCardLast4 = cleanLast4.ifEmpty { null },
                    trackingNumber = cleanTracking.ifEmpty { null },
                    receiptLocalPath = receiptLocalPath ?: existing.receiptLocalPath,
                    rejectionReasonFa = null,
                    rejectionReasonEn = null,
                    submittedAtMs = now
                )
                pollAttempts[orderId] = 0
                orderDao.upsert(updated.toEntity())
                updated
            }
        }
    }

    override suspend fun refreshOrderStatus(orderId: String): Result<PaymentOrder> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = orderDao.getById(orderId)?.toDomain()
                    ?: error("Order not found")
                delay(350L)

                val now = System.currentTimeMillis()
                val effective = OrderStateMachine.evaluateEffectiveStatus(current, now)
                if (effective != current.status) {
                    val expired = current.copy(status = effective)
                    orderDao.upsert(expired.toEntity())
                    return@runCatching expired
                }

                // Realistic demo simulation while in PENDING_REVIEW:
                // - Tracking number starting with "0000" simulates rejection so retry UX can be tested.
                // - Any normal tracking number auto-approves after 2 polls (~7s).
                if (current.status == OrderStatus.PENDING_REVIEW) {
                    val count = (pollAttempts[orderId] ?: 0) + 1
                    pollAttempts[orderId] = count
                    if (count >= 2) {
                        val isSimulatedReject = current.trackingNumber?.startsWith("0000") == true
                        val next = if (isSimulatedReject) {
                            current.copy(
                                status = OrderStatus.REJECTED,
                                rejectionReasonFa = "شماره پیگیری با مبلغ واریزی مطابقت نداشت. لطفاً رسید صحیح را مجدد ارسال کنید.",
                                rejectionReasonEn = "Tracking number did not match the transfer amount. Please re-submit your receipt.",
                                reviewedAtMs = now
                            )
                        } else {
                            current.copy(
                                status = OrderStatus.APPROVED,
                                reviewedAtMs = now
                            )
                        }
                        orderDao.upsert(next.toEntity())
                        return@runCatching next
                    }
                }
                current
            }
        }
    }

    override fun observeOrders(): Flow<List<PaymentOrder>> =
        orderDao.observeAll().map { list ->
            val now = System.currentTimeMillis()
            list.map { entity ->
                val domain = entity.toDomain()
                val eff = OrderStateMachine.evaluateEffectiveStatus(domain, now)
                if (eff != domain.status) domain.copy(status = eff) else domain
            }
        }

    override fun observeOrderById(orderId: String): Flow<PaymentOrder?> =
        orderDao.observeById(orderId).map { entity ->
            entity?.toDomain()?.let { domain ->
                val eff = OrderStateMachine.evaluateEffectiveStatus(domain)
                if (eff != domain.status) domain.copy(status = eff) else domain
            }
        }

    override suspend fun getOrderById(orderId: String): PaymentOrder? = withContext(Dispatchers.IO) {
        orderDao.getById(orderId)?.toDomain()
    }

    override suspend fun getActivePendingOrder(): PaymentOrder? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val candidate = orderDao.getLatestActiveOrder()?.toDomain() ?: return@withContext null
        val eff = OrderStateMachine.evaluateEffectiveStatus(candidate, now)
        if (eff == OrderStatus.EXPIRED && candidate.status != OrderStatus.EXPIRED) {
            orderDao.upsert(candidate.copy(status = OrderStatus.EXPIRED).toEntity())
            return@withContext null
        }
        candidate.takeIf { it.status == OrderStatus.AWAITING_RECEIPT || it.status == OrderStatus.PENDING_REVIEW }
    }

    override suspend fun simulateOrderStatus(
        orderId: String,
        targetStatus: OrderStatus,
        reasonFa: String?,
        reasonEn: String?
    ): Result<PaymentOrder> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = orderDao.getById(orderId)?.toDomain() ?: error("Order not found")
                val now = System.currentTimeMillis()
                val updated = current.copy(
                    status = targetStatus,
                    rejectionReasonFa = if (targetStatus == OrderStatus.REJECTED) {
                        reasonFa ?: "تصویر رسید ناخوانا بود. لطفاً مجدداً ارسال نمایید."
                    } else null,
                    rejectionReasonEn = if (targetStatus == OrderStatus.REJECTED) {
                        reasonEn ?: "Receipt image was unclear. Please submit again."
                    } else null,
                    reviewedAtMs = if (targetStatus == OrderStatus.APPROVED || targetStatus == OrderStatus.REJECTED) now else current.reviewedAtMs,
                    expiresAtMs = if (targetStatus == OrderStatus.EXPIRED) now - 1_000L else current.expiresAtMs
                )
                orderDao.upsert(updated.toEntity())
                updated
            }
        }
    }

    override suspend fun onOrderStatusPushReceived(orderId: String, newStatus: String) {
        val parsed = runCatching { OrderStatus.valueOf(newStatus.uppercase()) }.getOrNull() ?: return
        simulateOrderStatus(orderId, parsed)
    }

    // --- AccountActionsRepository (backend-ready simulation) ---

    override suspend fun listConnectedDevices(): Result<List<ConnectedDevice>> = withContext(Dispatchers.IO) {
        delay(250L)
        mutex.withLock { Result.success(fakeDevices.toList()) }
    }

    override suspend fun removeDevice(deviceId: String): Result<AccountActionResult> = withContext(Dispatchers.IO) {
        delay(300L)
        mutex.withLock {
            val target = fakeDevices.firstOrNull { it.id == deviceId }
            if (target == null) {
                return@withLock Result.success(
                    AccountActionResult(false, "دستگاه یافت نشد.", "Device not found.")
                )
            }
            if (target.isCurrentDevice) {
                return@withLock Result.success(
                    AccountActionResult(
                        false,
                        "دستگاه فعلی را نمی‌توانید حذف کنید.",
                        "Cannot remove the current active device."
                    )
                )
            }
            fakeDevices.removeAll { it.id == deviceId }
            Result.success(
                AccountActionResult(
                    true,
                    "دستگاه «${target.name}» با موفقیت حذف شد.",
                    "Device \"${target.name}\" removed."
                )
            )
        }
    }

    override suspend fun requestNewSubscriptionLink(): Result<AccountActionResult> = withContext(Dispatchers.IO) {
        delay(450L)
        Result.success(
            AccountActionResult(
                success = true,
                messageFa = "درخواست صدور لینک اشتراک جدید ثبت شد. لینک فعلی شما تا تأیید سرور فعال می‌ماند.",
                messageEn = "New subscription link request recorded. Your current link stays active until backend rotation."
            )
        )
    }

    override suspend fun submitSupportTicket(request: SupportTicketRequest): Result<AccountActionResult> =
        withContext(Dispatchers.IO) {
            delay(400L)
            val ticketCode = "TKT-${UUID.randomUUID().toString().take(6).uppercase()}"
            Result.success(
                AccountActionResult(
                    success = true,
                    messageFa = "تیکت شما با کد $ticketCode ثبت شد. پشتیبانی به‌زودی پاسخ می‌دهد.",
                    messageEn = "Ticket $ticketCode created. Support will reply shortly."
                )
            )
        }

    private fun fallbackCatalog(): PlansCatalogConfig = PlansCatalogConfig(
        currency = "TOMAN",
        paymentWindowMinutes = 30,
        destinationCard = DestinationCardInfo(
            cardNumber = "6037997512348901",
            holderNameFa = "شرکت فناوری پاسارگارد نتورک",
            holderNameEn = "PasarGuard Network Tech",
            bankNameFa = "بانک ملی ایران",
            bankNameEn = "Bank Melli Iran"
        ),
        plans = listOf(
            CatalogPlan(
                id = "renew_1m_50g",
                category = PlanCategory.RENEW,
                titleFa = "یک‌ماهه • ۵۰ گیگابایت",
                titleEn = "1 Month • 50 GB",
                durationDays = 30,
                dataLimitGb = 50,
                priceToman = 165_000L,
                oldPriceToman = 195_000L,
                maxDevices = 2
            ),
            CatalogPlan(
                id = "renew_3m_150g",
                category = PlanCategory.RENEW,
                titleFa = "سه‌ماهه • ۱۵۰ گیگابایت",
                titleEn = "3 Months • 150 GB",
                durationDays = 90,
                dataLimitGb = 150,
                priceToman = 420_000L,
                oldPriceToman = 525_000L,
                tagFa = "محبوب‌ترین",
                tagEn = "Most Popular",
                isPopular = true,
                maxDevices = 3
            )
        )
    )
}

private fun OrderEntity.toDomain(): PaymentOrder = PaymentOrder(
    id = id,
    idempotencyKey = idempotencyKey,
    planId = planId,
    planTitleFa = planTitleFa,
    planTitleEn = planTitleEn,
    planCategory = runCatching { PlanCategory.valueOf(planCategory) }.getOrDefault(PlanCategory.RENEW),
    paymentMethod = runCatching { PaymentMethodType.valueOf(paymentMethod) }.getOrDefault(PaymentMethodType.CARD_TO_CARD),
    status = runCatching { OrderStatus.valueOf(status) }.getOrDefault(OrderStatus.AWAITING_RECEIPT),
    basePriceToman = basePriceToman,
    discountCode = discountCode,
    discountAmountToman = discountAmountToman,
    finalPriceToman = finalPriceToman,
    destinationCardNumber = destinationCardNumber,
    destinationCardHolderFa = destinationCardHolderFa,
    destinationCardHolderEn = destinationCardHolderEn,
    destinationBankFa = destinationBankFa,
    destinationBankEn = destinationBankEn,
    payerCardLast4 = payerCardLast4,
    trackingNumber = trackingNumber,
    receiptLocalPath = receiptLocalPath,
    rejectionReasonFa = rejectionReasonFa,
    rejectionReasonEn = rejectionReasonEn,
    createdAtMs = createdAtMs,
    expiresAtMs = expiresAtMs,
    submittedAtMs = submittedAtMs,
    reviewedAtMs = reviewedAtMs
)

private fun PaymentOrder.toEntity(): OrderEntity = OrderEntity(
    id = id,
    idempotencyKey = idempotencyKey,
    planId = planId,
    planTitleFa = planTitleFa,
    planTitleEn = planTitleEn,
    planCategory = planCategory.name,
    paymentMethod = paymentMethod.name,
    status = status.name,
    basePriceToman = basePriceToman,
    discountCode = discountCode,
    discountAmountToman = discountAmountToman,
    finalPriceToman = finalPriceToman,
    destinationCardNumber = destinationCardNumber,
    destinationCardHolderFa = destinationCardHolderFa,
    destinationCardHolderEn = destinationCardHolderEn,
    destinationBankFa = destinationBankFa,
    destinationBankEn = destinationBankEn,
    payerCardLast4 = payerCardLast4,
    trackingNumber = trackingNumber,
    receiptLocalPath = receiptLocalPath,
    rejectionReasonFa = rejectionReasonFa,
    rejectionReasonEn = rejectionReasonEn,
    createdAtMs = createdAtMs,
    expiresAtMs = expiresAtMs,
    submittedAtMs = submittedAtMs,
    reviewedAtMs = reviewedAtMs
)
