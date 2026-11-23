package com.pasarguard.vpn.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Future Billing & Customer Account Backend Contract (Stage 2B — B5).
 *
 * Authentication model:
 * - Customer-scoped requests MUST authenticate with `Authorization: Bearer <customer_session_jwt>`
 *   or `X-Subscription-Token: <signed_sub_token>`.
 * - NEVER embed panel admin credentials or admin tokens in the Android app.
 * - Order creation MUST include `Idempotency-Key` (UUID v4) so network retries never create duplicate orders.
 *
 * See `android-client/API_CONTRACT.md` for full HTTP status codes, rate limits, and webhook approval flow.
 */
interface BillingBackendApi {

    @GET("v1/billing/plans")
    suspend fun listPlans(@HeaderMap auth: Map<String, String>): PlansCatalogResponseDto

    @POST("v1/billing/discounts/validate")
    suspend fun validateDiscountCode(
        @Header("X-Subscription-Token") subscriptionToken: String,
        @Body request: ValidateDiscountRequestDto
    ): ValidateDiscountResponseDto

    @POST("v1/billing/orders")
    suspend fun createOrder(
        @HeaderMap auth: Map<String, String>,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateOrderRequestDto
    ): OrderDto

    @Multipart
    @POST("v1/billing/orders/{orderId}/receipt")
    suspend fun uploadOrderReceipt(
        @HeaderMap auth: Map<String, String>,
        @Path("orderId") orderId: String,
        @Part("payer_card_last4") payerCardLast4: RequestBody,
        @Part("tracking_number") trackingNumber: RequestBody,
        @Part receiptImage: MultipartBody.Part?
    ): OrderDto

    @GET("v1/billing/orders/{orderId}")
    suspend fun getOrderStatus(
        @HeaderMap auth: Map<String, String>,
        @Path("orderId") orderId: String
    ): OrderDto

    @GET("v1/billing/orders")
    suspend fun listOrders(
        @HeaderMap auth: Map<String, String>,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 50
    ): OrdersListResponseDto

    @GET("v1/billing/orders/{orderId}/subscription")
    suspend fun getPurchasedSubscription(
        @HeaderMap auth: Map<String, String>,
        @Path("orderId") orderId: String
    ): PurchasedSubscriptionDto

    @GET("v1/account/devices")
    suspend fun listDevices(
        @Header("X-Subscription-Token") subscriptionToken: String
    ): DevicesListResponseDto

    @DELETE("v1/account/devices/{deviceId}")
    suspend fun removeDevice(
        @Header("X-Subscription-Token") subscriptionToken: String,
        @Path("deviceId") deviceId: String
    ): AccountActionResponseDto

    @POST("v1/account/regenerate-link")
    suspend fun requestNewSubscriptionLink(
        @Header("X-Subscription-Token") subscriptionToken: String
    ): AccountActionResponseDto

    @POST("v1/support/tickets")
    suspend fun submitSupportTicket(
        @Header("X-Subscription-Token") subscriptionToken: String,
        @Body request: SupportTicketRequestDto
    ): AccountActionResponseDto

    @GET("v1/app/config")
    suspend fun getAppRemoteConfig(
        @Query("platform") platform: String = "android",
        @Query("version_code") versionCode: Int
    ): AppRemoteConfigDto
}

/**
 * Push-ready hook for future Firebase Cloud Messaging / WebSocket order approval events.
 * When push is wired later, invoke [onOrderStatusPushReceived] from your messaging service.
 */
interface OrderPushEventReceiver {
    suspend fun onOrderStatusPushReceived(orderId: String, newStatus: String)
}

@Serializable
data class PlansCatalogResponseDto(
    val currency: String = "TOMAN",
    @SerialName("payment_window_minutes") val paymentWindowMinutes: Int = 30,
    @SerialName("destination_card") val destinationCard: DestinationCardDto,
    val plans: List<PlanDto> = emptyList()
)

@Serializable
data class DestinationCardDto(
    @SerialName("card_number") val cardNumber: String,
    @SerialName("holder_name_fa") val holderNameFa: String,
    @SerialName("holder_name_en") val holderNameEn: String,
    @SerialName("bank_name_fa") val bankNameFa: String,
    @SerialName("bank_name_en") val bankNameEn: String
)

@Serializable
data class PlanDto(
    val id: String,
    val category: String,
    @SerialName("title_fa") val titleFa: String,
    @SerialName("title_en") val titleEn: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("data_limit_gb") val dataLimitGb: Int? = null,
    @SerialName("price_toman") val priceToman: Long,
    @SerialName("old_price_toman") val oldPriceToman: Long? = null,
    @SerialName("tag_fa") val tagFa: String? = null,
    @SerialName("tag_en") val tagEn: String? = null,
    @SerialName("is_popular") val isPopular: Boolean = false,
    @SerialName("is_best_value") val isBestValue: Boolean = false,
    @SerialName("max_devices") val maxDevices: Int? = null,
    @SerialName("features_fa") val featuresFa: List<String> = emptyList(),
    @SerialName("features_en") val featuresEn: List<String> = emptyList()
)

@Serializable
data class ValidateDiscountRequestDto(
    @SerialName("plan_id") val planId: String,
    val code: String
)

@Serializable
data class ValidateDiscountResponseDto(
    val valid: Boolean,
    val code: String,
    @SerialName("discount_amount_toman") val discountAmountToman: Long = 0L,
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("message_fa") val messageFa: String = "",
    @SerialName("message_en") val messageEn: String = ""
)

@Serializable
data class CreateOrderRequestDto(
    @SerialName("plan_id") val planId: String,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("discount_code") val discountCode: String? = null
)

@Serializable
data class OrderDto(
    val id: String,
    val status: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_title_fa") val planTitleFa: String,
    @SerialName("plan_title_en") val planTitleEn: String,
    @SerialName("plan_category") val planCategory: String,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("base_price_toman") val basePriceToman: Long,
    @SerialName("discount_code") val discountCode: String? = null,
    @SerialName("discount_amount_toman") val discountAmountToman: Long = 0L,
    @SerialName("final_price_toman") val finalPriceToman: Long,
    @SerialName("destination_card") val destinationCard: DestinationCardDto,
    @SerialName("payer_card_last4") val payerCardLast4: String? = null,
    @SerialName("tracking_number") val trackingNumber: String? = null,
    @SerialName("rejection_reason_fa") val rejectionReasonFa: String? = null,
    @SerialName("rejection_reason_en") val rejectionReasonEn: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
    @SerialName("submitted_at_ms") val submittedAtMs: Long? = null,
    @SerialName("reviewed_at_ms") val reviewedAtMs: Long? = null,
    @SerialName("new_subscription") val newSubscription: Boolean = false,
    @SerialName("activation_status") val activationStatus: String? = null,
    @SerialName("has_receipt_image") val hasReceiptImage: Boolean = false
)

@Serializable
data class PurchasedSubscriptionDto(@SerialName("subscription_url") val subscriptionUrl: String)

@Serializable
data class OrdersListResponseDto(
    val orders: List<OrderDto> = emptyList()
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val model: String,
    @SerialName("last_seen_ms") val lastSeenMs: Long,
    @SerialName("is_current") val isCurrent: Boolean = false
)

@Serializable
data class DevicesListResponseDto(
    val devices: List<DeviceDto> = emptyList()
)

@Serializable
data class AccountActionResponseDto(
    val success: Boolean,
    @SerialName("message_fa") val messageFa: String,
    @SerialName("message_en") val messageEn: String,
    @SerialName("new_subscription_url") val newSubscriptionUrl: String? = null
)

@Serializable
data class SupportTicketRequestDto(
    val subject: String,
    val message: String,
    @SerialName("order_id") val orderId: String? = null
)

@Serializable
data class AppRemoteConfigDto(
    @SerialName("latest_version") val latestVersion: String,
    @SerialName("latest_version_code") val latestVersionCode: Int = 0,
    @SerialName("download_url") val downloadUrl: String = "",
    val changelog: String = "",
    @SerialName("force_update") val forceUpdate: Boolean = false,
    val announcement: String? = null
)
