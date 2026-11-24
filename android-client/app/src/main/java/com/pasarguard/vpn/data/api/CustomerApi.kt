package com.pasarguard.vpn.data.api

import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.data.local.SecureTokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CustomerWallet(
    @SerialName("balance_toman") val balance: Long,
    val refunds: List<CustomerRefund> = emptyList()
)

@Serializable
data class CustomerRefund(@SerialName("order_id") val orderId: String, val amount: Long? = null, val finished: Long? = null)

@Serializable
data class CancelQuote(val quote: String, val amount: Long, val balance: Long)

class CustomerRequestException(val status: Int, val code: String?) : Exception()

@Singleton
class CustomerApi @Inject constructor(private val store: SecureTokenStore, private val json: Json) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    val signedIn: Boolean get() = store.customerToken() != null

    suspend fun sendCode(email: String) = request("/v1/customer/code", mapOf("email" to email))

    suspend fun login(email: String, password: String, code: String?) {
        val fields = mutableMapOf("email" to email, "password" to password)
        if (code != null) fields["code"] = code
        val data = request(if (code == null) "/v1/customer/login" else "/v1/customer/verify", fields)
        val token = data["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        store.saveCustomerToken(token)
    }

    suspend fun email() = json.decodeFromJsonElement(
        Email.serializer(), request("/v1/customer/me", auth = true)
    ).email

    suspend fun wallet() = json.decodeFromJsonElement(
        CustomerWallet.serializer(), request("/v1/customer/wallet", auth = true)
    )

    suspend fun orders() = json.decodeFromJsonElement(
        OrdersListResponseDto.serializer(), request("/v1/billing/orders", auth = true)
    ).orders

    suspend fun subscription(orderId: String) = json.decodeFromJsonElement(
        PurchasedSubscriptionDto.serializer(), request("/v1/billing/orders/$orderId/subscription", auth = true)
    ).subscriptionUrl

    suspend fun cancelPreview(orderId: String) = json.decodeFromJsonElement(
        CancelQuote.serializer(), request("/v1/customer/orders/$orderId/cancel-preview", auth = true)
    )

    suspend fun cancel(orderId: String, quote: String) =
        request("/v1/customer/orders/$orderId/cancel", mapOf("quote" to quote), auth = true)

    suspend fun transfer(orderId: String) {
        val purchase = store.existingPurchaseToken() ?: throw CustomerRequestException(404, null)
        val data = request("/v1/billing/orders/$orderId/transfer-ticket", emptyMap(), purchase = purchase)
        request("/v1/customer/claim", mapOf("ticket" to data.getValue("ticket").jsonPrimitive.content), auth = true)
    }

    suspend fun logout() {
        try {
            request("/v1/customer/logout", emptyMap(), auth = true)
        } finally {
            store.saveCustomerToken(null)
        }
    }

    private suspend fun request(
        path: String, fields: Map<String, String>? = null, auth: Boolean = false, purchase: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val origin = BuildConfig.BILLING_BASE_URL.toHttpUrl()
        require(origin.isHttps)
        val url = origin.newBuilder().encodedPath(path).build()
        val builder = Request.Builder().url(url).header("X-Pasarguard-Client", "native")
        if (auth) {
            val token = store.customerToken() ?: throw CustomerRequestException(401, null)
            builder.header("Authorization", "Bearer $token")
        }
        if (purchase != null) builder.header("X-Purchase-Token", purchase)
        if (fields != null) {
            val body = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
            builder.post(body.toRequestBody("application/json".toMediaType()))
        }
        http.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.source()?.let { source ->
                check(!source.request(262_145))
                source.readUtf8()
            }.orEmpty()
            val data = payload.takeIf { it.isNotBlank() }?.let {
                runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
            } ?: JsonObject(emptyMap())
            if (!response.isSuccessful) {
                if (auth && response.code == 401) store.saveCustomerToken(null)
                throw CustomerRequestException(response.code, (data["detail"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull)
            }
            data
        }
    }

    @Serializable private data class Email(val email: String)
}
