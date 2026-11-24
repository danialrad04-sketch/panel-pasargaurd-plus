package com.pasarguard.vpn.data.repository

import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.data.api.BillingBackendApi
import com.pasarguard.vpn.data.api.CreateOrderRequestDto
import com.pasarguard.vpn.data.api.DestinationCardDto
import com.pasarguard.vpn.data.api.OrderDto
import com.pasarguard.vpn.data.local.SecureTokenStore
import com.pasarguard.vpn.domain.model.CatalogPlan
import com.pasarguard.vpn.domain.model.CheckoutSession
import com.pasarguard.vpn.domain.model.DestinationCardInfo
import com.pasarguard.vpn.domain.model.DiscountValidationResult
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentMethodType
import com.pasarguard.vpn.domain.model.newestPaymentStatus
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemotePaymentRepository @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    private val tokens: SecureTokenStore,
    json: Json
) : PaymentRepository {
    private data class BillingAuth(val token: String, val guest: Boolean, val session: String? = null) {
        fun headers(useSubscription: Boolean = false): Map<String, String> = buildMap {
            if (session != null) put("Authorization", "Bearer $session")
            if (!guest && (session == null || useSubscription)) put("X-Subscription-Token", token)
            if (guest && session == null) put("X-Purchase-Token", token)
        }
    }
    private val orders = MutableStateFlow<Map<BillingAuth, List<PaymentOrder>>>(emptyMap())
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

    private fun auth(): BillingAuth {
        val session = tokens.customerToken()
        val credentials = subscriptions.getSavedCredentials()
            ?: return BillingAuth(if (session == null) tokens.purchaseToken() else "", true, session)
        check(credentials.baseUrl.toHttpUrl().host == BrandConfig.defaultBaseUrl.toHttpUrl().host && credentials.baseUrl.toHttpUrl().isHttps) {
            "برای تمدید، لینک اشتراک صادرشده از پنل پاسارگارد را وارد کنید."
        }
        return BillingAuth(credentials.token, false, session)
    }

    private suspend fun <T> request(block: suspend (BillingAuth) -> T): Result<T> = try {
        val owner = auth()
        val value = block(owner)
        check(owner == auth()) { "The active subscription changed" }
        Result.success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun savedGuest(): BillingAuth? = tokens.existingPurchaseToken()?.let { BillingAuth(it, true) }

    private fun accessibleOrders(owner: BillingAuth): List<BillingAuth> =
        listOfNotNull(owner, if (!owner.guest || owner.session != null) savedGuest() else null,
            if (!owner.guest && owner.session != null) owner.copy(session = null) else null)

    private suspend fun orderRequest(block: suspend (BillingAuth) -> PaymentOrder) = request { owner ->
        var missing: HttpException? = null
        for (source in accessibleOrders(owner)) {
            try {
                return@request block(source).also { remember(source, it) }
            } catch (error: HttpException) {
                if (error.code() != 404) throw error
                missing = error
            }
        }
        throw checkNotNull(missing)
    }

    override suspend fun loadPlansCatalog() = try {
        val catalog = api.listPlans(mapOf("X-Purchase-Token" to tokens.purchaseToken()))
        Result.success(PlansCatalogConfig(catalog.currency, catalog.paymentWindowMinutes, catalog.destinationCard.toDomain(),
            catalog.plans.map {
                CatalogPlan(it.id, PlanCategory.valueOf(it.category), it.titleFa, it.titleEn,
                    it.durationDays, it.dataLimitGb, it.priceToman, it.oldPriceToman, it.tagFa,
                    it.tagEn, it.isPopular, it.isBestValue, it.maxDevices, it.featuresFa, it.featuresEn)
            }))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
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
        api.createOrder(owner.headers(useSubscription = true), idempotencyKey, CreateOrderRequestDto(plan.id, paymentMethod.name, discountCode))
            .toDomain(idempotencyKey).also { remember(owner, it) }
    }

    override suspend fun submitCardReceipt(
        orderId: String, payerCardLast4: String, trackingNumber: String, receiptLocalPath: String?
    ) = orderRequest { owner ->
        val plain = "text/plain".toMediaType()
        val attachment = receiptLocalPath?.let {
            MultipartBody.Part.createFormData(
                "receipt_image", "receipt.jpg", File(it).asRequestBody("image/jpeg".toMediaType())
            )
        }
        api.uploadOrderReceipt(owner.headers(), orderId, payerCardLast4.toRequestBody(plain),
            trackingNumber.trim().toRequestBody(plain), attachment).toDomain()
    }

    override suspend fun refreshOrderStatus(orderId: String) = orderRequest { owner ->
        api.getOrderStatus(owner.headers(), orderId).toDomain()
    }

    override suspend fun getPurchasedSubscription(orderId: String) = request { owner ->
        for (source in accessibleOrders(owner).filter { it.guest || it.session != null }) {
            try {
                return@request api.getPurchasedSubscription(source.headers(), orderId).subscriptionUrl
            } catch (error: HttpException) {
                if (error.code() != 404) throw error
            }
        }
        error("Subscription does not belong to this account")
    }

    private fun remember(owner: BillingAuth, order: PaymentOrder) {
        val active = runCatching(::auth).getOrNull() ?: return
        val allowed = accessibleOrders(active)
        if (owner !in allowed) return
        orders.update { cache ->
            val known = cache[owner].orEmpty()
            val newest = newestPaymentStatus(known.firstOrNull { it.id == order.id }, order)
            cache.filterKeys { it in allowed } + (owner to (known.filterNot { it.id == order.id } + newest))
        }
    }

    private fun visibleOrders(): List<PaymentOrder> {
        val owner = runCatching(::auth).getOrNull() ?: return emptyList()
        return accessibleOrders(owner).flatMap { orders.value[it].orEmpty() }.distinctBy { it.id }.sortedByDescending { it.createdAtMs }
    }

    override fun observeOrders(): Flow<List<PaymentOrder>> = combine(orders, flow {
        while (true) {
            val owner = runCatching(::auth).getOrNull()
            emit(Unit)
            if (owner != null) {
                accessibleOrders(owner).forEach { source ->
                    request { active ->
                        check(active == owner)
                        api.listOrders(source.headers()).orders.map { order -> order.toDomain() }
                    }.onSuccess { remote ->
                        val current = runCatching(::auth).getOrNull()
                        if (current == owner) {
                            val allowed = accessibleOrders(current)
                            orders.update { cached ->
                                val previous = cached[source].orEmpty().associateBy { it.id }
                                cached.filterKeys { it in allowed } +
                                    (source to remote.map { newestPaymentStatus(previous[it.id], it) })
                            }
                        }
                    }
                }
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
        api.listOrders(owner.headers()).orders.map { it.toDomain() }
            .firstOrNull { it.status == OrderStatus.AWAITING_RECEIPT || it.status == OrderStatus.PENDING_REVIEW ||
                owner.guest && it.newSubscription && it.status == OrderStatus.APPROVED }
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
        createdAtMs = createdAtMs, expiresAtMs = expiresAtMs, submittedAtMs = submittedAtMs, reviewedAtMs = reviewedAtMs,
        newSubscription = newSubscription, activationStatus = activationStatus, activationReadyAtMs = activationReadyAtMs, hasReceiptImage = hasReceiptImage
    )
}
