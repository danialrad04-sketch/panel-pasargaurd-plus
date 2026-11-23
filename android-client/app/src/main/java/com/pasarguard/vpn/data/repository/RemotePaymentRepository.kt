package com.pasarguard.vpn.data.repository

import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.data.api.BillingBackendApi
import com.pasarguard.vpn.data.api.CreateOrderRequestDto
import com.pasarguard.vpn.data.api.DestinationCardDto
import com.pasarguard.vpn.data.api.OrderDto
import com.pasarguard.vpn.domain.model.CatalogPlan
import com.pasarguard.vpn.domain.model.CheckoutSession
import com.pasarguard.vpn.domain.model.DestinationCardInfo
import com.pasarguard.vpn.domain.model.DiscountValidationResult
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentMethodType
import com.pasarguard.vpn.domain.model.PaymentOrder
import com.pasarguard.vpn.domain.model.PlanCategory
import com.pasarguard.vpn.domain.model.PlansCatalogConfig
import com.pasarguard.vpn.domain.model.RenewalPlan
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemotePaymentRepository @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    json: Json
) : PaymentRepository {
    private val orders = MutableStateFlow<Pair<String, List<PaymentOrder>>?>(null)
    private val api by lazy {
        check(BuildConfig.BILLING_BASE_URL.isNotBlank()) { "Billing service is not configured" }
        val url = BuildConfig.BILLING_BASE_URL.trimEnd('/').plus("/").toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        Retrofit.Builder()
            .baseUrl(url)
            .client(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(BillingBackendApi::class.java)
    }

    private fun token(): String {
        val credentials = checkNotNull(subscriptions.getSavedCredentials()) { "Open a subscription first" }
        check(credentials.baseUrl.trimEnd('/') == BrandConfig.defaultBaseUrl.trimEnd('/')) {
            "Payment is available only for subscriptions on the configured panel"
        }
        return credentials.token
    }

    private suspend fun <T> request(block: suspend (String) -> T): Result<T> = try {
        val owner = token()
        val value = block(owner)
        check(owner == token()) { "The active subscription changed" }
        Result.success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    override suspend fun loadPlansCatalog() = request { owner ->
        val catalog = api.listPlans(owner)
        PlansCatalogConfig(catalog.currency, catalog.paymentWindowMinutes, catalog.destinationCard.toDomain(),
            catalog.plans.map {
                CatalogPlan(it.id, PlanCategory.valueOf(it.category), it.titleFa, it.titleEn,
                    it.durationDays, it.dataLimitGb, it.priceToman, it.oldPriceToman, it.tagFa,
                    it.tagEn, it.isPopular, it.isBestValue, it.maxDevices, it.featuresFa, it.featuresEn)
            })
    }

    override suspend fun getAvailablePlans() = loadPlansCatalog().map { catalog ->
        catalog.plans.filter { it.category == PlanCategory.RENEW }.map {
            RenewalPlan(
                id = it.id, titleEn = it.titleEn, titleFa = it.titleFa, durationDays = it.durationDays,
                dataLimitGb = it.dataLimitGb, priceDisplayEn = "${Formatters.formatNumber(it.priceToman, false)} Toman",
                priceDisplayFa = "${Formatters.formatNumber(it.priceToman, true)} تومان",
                isPopular = it.isPopular, badgeEn = it.tagEn, badgeFa = it.tagFa
            )
        }
    }

    override suspend fun createCheckoutSession(planId: String, subscriptionToken: String, callbackUrl: String):
        Result<CheckoutSession> = Result.failure(UnsupportedOperationException("Open Plans for card-to-card checkout"))

    override suspend fun validateDiscountCode(planId: String, code: String) = Result.success(
        DiscountValidationResult(code, false, messageFa = "کد تخفیف در دسترس نیست.",
            messageEn = "Discount codes are unavailable.")
    )

    override suspend fun createOrder(
        plan: CatalogPlan, paymentMethod: PaymentMethodType, discountCode: String?, idempotencyKey: String
    ) = request { owner ->
        api.createOrder(owner, idempotencyKey, CreateOrderRequestDto(plan.id, paymentMethod.name, discountCode))
            .toDomain(idempotencyKey).also { remember(owner, it) }
    }

    override suspend fun submitCardReceipt(
        orderId: String, payerCardLast4: String, trackingNumber: String, receiptLocalPath: String?
    ) = request { owner ->
        val plain = "text/plain".toMediaType()
        api.uploadOrderReceipt(owner, orderId, payerCardLast4.toRequestBody(plain),
            trackingNumber.trim().toRequestBody(plain), null).toDomain().also { remember(owner, it) }
    }

    override suspend fun refreshOrderStatus(orderId: String) = request { owner ->
        api.getOrderStatus(owner, orderId).toDomain().also { remember(owner, it) }
    }

    private fun remember(owner: String, order: PaymentOrder) {
        if (runCatching(::token).getOrNull() != owner) return
        orders.update { cache ->
            val known = cache?.takeIf { it.first == owner }?.second.orEmpty()
            val prior = known.firstOrNull { it.id == order.id }
            val newest = if (prior?.status == OrderStatus.APPROVED) prior else order
            owner to (known.filterNot { it.id == order.id } + newest).sortedByDescending { it.createdAtMs }
        }
    }

    private fun visibleOrders(): List<PaymentOrder> =
        orders.value?.takeIf { it.first == runCatching(::token).getOrNull() }?.second.orEmpty()

    override fun observeOrders(): Flow<List<PaymentOrder>> = combine(orders, flow {
        while (true) {
            val owner = runCatching(::token).getOrNull()
            emit(Unit)
            if (owner != null) {
                request { api.listOrders(it).orders.map { order -> order.toDomain() } }
                    .onSuccess { remote -> remote.forEach { remember(owner, it) } }
            }
            delay(15_000)
        }
    }) { _, _ -> visibleOrders() }

    override fun observeOrderById(orderId: String): Flow<PaymentOrder?> =
        combine(orders, subscriptions.observeSubscription()) { _, _ ->
            visibleOrders().firstOrNull { it.id == orderId }
        }

    override suspend fun getOrderById(orderId: String) = refreshOrderStatus(orderId).getOrNull()

    override suspend fun getActivePendingOrder() = request { owner ->
        api.listOrders(owner).orders.map { it.toDomain() }
            .firstOrNull { it.status == OrderStatus.AWAITING_RECEIPT || it.status == OrderStatus.PENDING_REVIEW }
    }.getOrNull()

    override suspend fun simulateOrderStatus(
        orderId: String, targetStatus: OrderStatus, reasonFa: String?, reasonEn: String?
    ): Result<PaymentOrder> = Result.failure(UnsupportedOperationException("Order status is managed by the server"))

    private fun DestinationCardDto.toDomain() =
        DestinationCardInfo(cardNumber, holderNameFa, holderNameEn, bankNameFa, bankNameEn)

    private fun OrderDto.toDomain(key: String = id) = PaymentOrder(
        id = id, idempotencyKey = key, planId = planId, planTitleFa = planTitleFa,
        planTitleEn = planTitleEn, planCategory = PlanCategory.valueOf(planCategory),
        paymentMethod = PaymentMethodType.valueOf(paymentMethod), status = OrderStatus.valueOf(status),
        basePriceToman = basePriceToman, discountCode = discountCode, discountAmountToman = discountAmountToman,
        finalPriceToman = finalPriceToman, destinationCardNumber = destinationCard.cardNumber,
        destinationCardHolderFa = destinationCard.holderNameFa, destinationCardHolderEn = destinationCard.holderNameEn,
        destinationBankFa = destinationCard.bankNameFa, destinationBankEn = destinationCard.bankNameEn,
        payerCardLast4 = payerCardLast4, trackingNumber = trackingNumber,
        rejectionReasonFa = rejectionReasonFa, rejectionReasonEn = rejectionReasonEn,
        createdAtMs = createdAtMs, expiresAtMs = expiresAtMs, submittedAtMs = submittedAtMs, reviewedAtMs = reviewedAtMs
    )
}
