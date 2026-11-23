package com.pasarguard.vpn.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
enum class PlanCategory {
    RENEW,
    UPGRADE,
    EXTRA_TRAFFIC,
    EXTRA_DAYS
}

@Serializable
data class DestinationCardInfo(
    val cardNumber: String,
    val holderNameFa: String,
    val holderNameEn: String,
    val bankNameFa: String,
    val bankNameEn: String
) {
    fun formattedCardNumber(): String =
        cardNumber.filter(Char::isDigit).chunked(4).joinToString("  ")
}

@Serializable
data class CatalogPlan(
    val id: String,
    val category: PlanCategory = PlanCategory.RENEW,
    val titleFa: String,
    val titleEn: String,
    val durationDays: Int = 30,
    val dataLimitGb: Int? = null, // null = unlimited, 0 = no extra GB (extra-days pack)
    val priceToman: Long,
    val oldPriceToman: Long? = null,
    val tagFa: String? = null,
    val tagEn: String? = null,
    val isPopular: Boolean = false,
    val isBestValue: Boolean = false,
    val maxDevices: Int? = null,
    val featuresFa: List<String> = emptyList(),
    val featuresEn: List<String> = emptyList()
) {
    val discountPercent: Int?
        get() {
            val old = oldPriceToman ?: return null
            if (old <= priceToman || old <= 0L) return null
            return (((old - priceToman).toDouble() / old.toDouble()) * 100.0).roundToInt()
        }

    val pricePerGbToman: Long?
        get() {
            val gb = dataLimitGb ?: return null
            if (gb <= 0) return null
            return priceToman / gb
        }

    val pricePerDayToman: Long?
        get() {
            if (durationDays <= 0) return null
            return priceToman / durationDays
        }

    val isUnlimitedData: Boolean
        get() = dataLimitGb == null
}

@Serializable
data class PlansCatalogConfig(
    val currency: String = "TOMAN",
    val paymentWindowMinutes: Int = 30,
    val destinationCard: DestinationCardInfo,
    val plans: List<CatalogPlan>
)

enum class PaymentMethodType {
    CARD_TO_CARD,
    ONLINE_GATEWAY,
    CRYPTO,
    WALLET
}

data class DiscountValidationResult(
    val code: String,
    val isValid: Boolean,
    val discountAmountToman: Long = 0L,
    val discountPercent: Int? = null,
    val messageFa: String = "",
    val messageEn: String = ""
)

@Serializable
enum class OrderStatus {
    AWAITING_RECEIPT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    EXPIRED;

    val isTerminal: Boolean
        get() = this == APPROVED || this == EXPIRED

    val canSubmitReceipt: Boolean
        get() = this == AWAITING_RECEIPT || this == REJECTED
}

@Serializable
data class OrderTimelineEvent(
    val titleFa: String,
    val titleEn: String,
    val timestampMs: Long,
    val completed: Boolean = true
)

data class PaymentOrder(
    val id: String,
    val idempotencyKey: String,
    val planId: String,
    val planTitleFa: String,
    val planTitleEn: String,
    val planCategory: PlanCategory,
    val paymentMethod: PaymentMethodType,
    val status: OrderStatus,
    val basePriceToman: Long,
    val discountCode: String? = null,
    val discountAmountToman: Long = 0L,
    val finalPriceToman: Long,
    val destinationCardNumber: String,
    val destinationCardHolderFa: String,
    val destinationCardHolderEn: String,
    val destinationBankFa: String,
    val destinationBankEn: String,
    val payerCardLast4: String? = null,
    val trackingNumber: String? = null,
    val receiptLocalPath: String? = null,
    val rejectionReasonFa: String? = null,
    val rejectionReasonEn: String? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val submittedAtMs: Long? = null,
    val reviewedAtMs: Long? = null,
    val newSubscription: Boolean = false,
    val activationStatus: String? = null,
    val hasReceiptImage: Boolean = false
) {
    fun remainingPaymentWindowMs(nowMs: Long = System.currentTimeMillis()): Long =
        max(0L, expiresAtMs - nowMs)

    fun isPaymentWindowExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == OrderStatus.AWAITING_RECEIPT && nowMs >= expiresAtMs

    fun buildTimeline(): List<OrderTimelineEvent> {
        val events = mutableListOf(
            OrderTimelineEvent(
                titleFa = "ثبت سفارش و رزرو مبلغ",
                titleEn = "Order created & amount reserved",
                timestampMs = createdAtMs,
                completed = true
            )
        )
        if (submittedAtMs != null) {
            events += OrderTimelineEvent(
                titleFa = "ارسال رسید و اطلاعات کارت‌به‌کارت",
                titleEn = "Card-to-card receipt submitted",
                timestampMs = submittedAtMs,
                completed = true
            )
        }
        when (status) {
            OrderStatus.APPROVED -> events += OrderTimelineEvent(
                titleFa = "تأیید پرداخت",
                titleEn = "Payment approved",
                timestampMs = reviewedAtMs ?: System.currentTimeMillis(),
                completed = true
            )
            OrderStatus.REJECTED -> events += OrderTimelineEvent(
                titleFa = "رد رسید توسط پشتیبانی (قابل ارسال مجدد)",
                titleEn = "Receipt rejected (retry available)",
                timestampMs = reviewedAtMs ?: System.currentTimeMillis(),
                completed = true
            )
            OrderStatus.EXPIRED -> events += OrderTimelineEvent(
                titleFa = "پایان مهلت پرداخت سفارش",
                titleEn = "Payment window expired",
                timestampMs = expiresAtMs,
                completed = true
            )
            OrderStatus.PENDING_REVIEW -> events += OrderTimelineEvent(
                titleFa = "در انتظار بررسی و تأیید مالی",
                titleEn = "Awaiting manual verification",
                timestampMs = submittedAtMs ?: createdAtMs,
                completed = false
            )
            OrderStatus.AWAITING_RECEIPT -> events += OrderTimelineEvent(
                titleFa = "در انتظار واریز و ثبت رسید",
                titleEn = "Waiting for transfer & receipt",
                timestampMs = createdAtMs,
                completed = false
            )
        }
        return events
    }
}

/**
 * Pure Kotlin state machine for card-to-card order lifecycle, expiry checks, and polling backoff.
 */
object OrderStateMachine {

    fun canTransition(from: OrderStatus, to: OrderStatus): Boolean = when (from) {
        OrderStatus.AWAITING_RECEIPT -> to == OrderStatus.PENDING_REVIEW || to == OrderStatus.EXPIRED
        OrderStatus.PENDING_REVIEW -> to == OrderStatus.APPROVED || to == OrderStatus.REJECTED || to == OrderStatus.EXPIRED
        OrderStatus.REJECTED -> to == OrderStatus.PENDING_REVIEW || to == OrderStatus.EXPIRED
        OrderStatus.APPROVED, OrderStatus.EXPIRED -> false
    }

    fun evaluateEffectiveStatus(order: PaymentOrder, nowMs: Long = System.currentTimeMillis()): OrderStatus {
        if (order.status == OrderStatus.AWAITING_RECEIPT && nowMs >= order.expiresAtMs) {
            return OrderStatus.EXPIRED
        }
        return order.status
    }

    fun nextPollDelayMs(attemptIndex: Int): Long {
        val schedule = longArrayOf(3_000L, 4_000L, 6_000L, 10_000L, 15_000L, 20_000L)
        return schedule[attemptIndex.coerceIn(0, schedule.lastIndex)]
    }
}

data class ConnectedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val model: String,
    val lastSeenEpochMs: Long,
    val isCurrentDevice: Boolean = false
)

data class AccountActionResult(
    val success: Boolean,
    val messageFa: String,
    val messageEn: String,
    val newSubscriptionUrl: String? = null
)

data class SupportTicketRequest(
    val subject: String,
    val message: String,
    val orderId: String? = null
)

data class UsageForecast(
    val averageDailyBytes: Long,
    val peakDayLabel: String?,
    val peakDayBytes: Long,
    val estimatedDaysRemaining: Int?,
    val willRunOutBeforeExpiry: Boolean,
    val recommendedPlanId: String?
)

/**
 * Pure Kotlin usage analytics & forecasting engine for Subscription Insights and Smart Plan Suggestions.
 */
object UsageForecastCalculator {

    private const val BYTES_PER_GB = 1024L * 1024L * 1024L

    fun calculate(
        subscription: SubscriptionInfo?,
        usagePoints: List<UsagePoint>,
        catalogPlans: List<CatalogPlan> = emptyList(),
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): UsageForecast {
        val nonZeroDays = usagePoints.filter { it.bytes > 0L }
        val avgDaily = when {
            usagePoints.isNotEmpty() -> {
                val divisor = max(1, usagePoints.size)
                usagePoints.sumOf { it.bytes } / divisor
            }
            subscription != null && subscription.usedTrafficBytes > 0L -> {
                // Fallback estimate when panel /usage endpoint returns empty: assume 14-day window
                subscription.usedTrafficBytes / 14L
            }
            else -> 0L
        }

        val peak = nonZeroDays.maxByOrNull { it.bytes }
        val remainingBytes = subscription?.remainingTrafficBytes
        val remainingDaysToExpiry = subscription?.remainingDays(nowEpochSeconds)

        val estimatedDaysLeft: Int? = when {
            subscription == null || subscription.isUnlimitedTraffic -> null
            remainingBytes == null -> null
            remainingBytes <= 0L -> 0
            avgDaily <= 0L -> null
            else -> (remainingBytes / avgDaily).toInt().coerceAtLeast(0)
        }

        val willRunOutEarly = estimatedDaysLeft != null &&
            remainingDaysToExpiry != null &&
            remainingDaysToExpiry > 0 &&
            estimatedDaysLeft < remainingDaysToExpiry

        val recommendedId = recommendPlanId(avgDaily, catalogPlans)

        return UsageForecast(
            averageDailyBytes = avgDaily,
            peakDayLabel = peak?.periodStart,
            peakDayBytes = peak?.bytes ?: 0L,
            estimatedDaysRemaining = estimatedDaysLeft,
            willRunOutBeforeExpiry = willRunOutEarly,
            recommendedPlanId = recommendedId
        )
    }

    fun recommendPlanId(averageDailyBytes: Long, plans: List<CatalogPlan>): String? {
        val mainPlans = plans.filter { it.category == PlanCategory.RENEW || it.category == PlanCategory.UPGRADE }
        if (mainPlans.isEmpty()) return null
        if (averageDailyBytes <= 0L) {
            return mainPlans.firstOrNull { it.isPopular }?.id ?: mainPlans.first().id
        }
        val monthlyProjectedGb = ceil((averageDailyBytes * 30.0) / BYTES_PER_GB).toInt().coerceAtLeast(1)
        val sortedByFit = mainPlans
            .filter { plan ->
                val gb = plan.dataLimitGb ?: return@filter true
                val months = max(1.0, plan.durationDays / 30.0)
                (gb / months) >= monthlyProjectedGb * 0.9
            }
            .sortedBy { it.pricePerDayToman ?: Long.MAX_VALUE }
        return sortedByFit.firstOrNull()?.id
            ?: mainPlans.firstOrNull { it.isUnlimitedData }?.id
            ?: mainPlans.firstOrNull { it.isPopular }?.id
    }
}
