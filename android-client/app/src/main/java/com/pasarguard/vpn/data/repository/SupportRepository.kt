package com.pasarguard.vpn.data.repository

import android.content.Context
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.data.local.SecureTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class SupportSummary(val id: String, val subject: String, val status: String, val unread: Int)
@Serializable data class SupportDetail(val id: String, val subject: String, val status: String, val messages: List<SupportMessage>)
@Serializable data class SupportMessage(val sequence: Long, val sender: String, val body: String)
@Serializable data class SupportDiagnostic(val network: String, val failure: String, val version: String = BuildConfig.VERSION_NAME)
@Serializable data class SupportRequest(
    val request_id: String,
    val subject: String,
    val body: String,
    val category: String,
    val order_id: String? = null,
    val diagnostic: SupportDiagnostic? = null
)
@Serializable private data class Identifier(val id: String)
@Serializable private data class Reply(val request_id: String, val body: String)
@Serializable private data class ReadThrough(val sequence: Long)
@Serializable private data class PushDevice(val installation_id: String, val token: String, val server_id: String?)
@Serializable data class ServiceIncident(
    val id: String,
    val title: String,
    val body: String,
    val guidance: String,
    val scope: String,
    val status: String,
    val server_ids: List<String>,
    val revision: Int
)
@Serializable data class ServiceStatus(val items: List<ServiceIncident> = emptyList(), val checkedAt: Long = 0, val stale: Boolean = false)

@Singleton
class SupportRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val tokens: SecureTokenStore
) {
    private val prefs = context.getSharedPreferences("service_status", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    val configured get() = BuildConfig.BILLING_BASE_URL.isNotBlank()
    val accountChanges get() = tokens.customerSessionChanges
    fun signedIn() = tokens.customerToken() != null

    suspend fun threads(offset: Int = 0): List<SupportSummary> = json.decodeFromString(call("/v1/customer/support?offset=$offset", authenticated = true))
    suspend fun detail(id: String, before: Long = Long.MAX_VALUE): SupportDetail =
        json.decodeFromString(call("/v1/customer/support/$id?before=$before", authenticated = true))
    suspend fun create(body: SupportRequest): String =
        json.decodeFromString<Identifier>(call("/v1/customer/support", json.encodeToString(body), authenticated = true)).id
    suspend fun reply(id: String, requestId: String, text: String) {
        call("/v1/customer/support/$id/messages", json.encodeToString(Reply(requestId, text)), authenticated = true)
    }
    suspend fun read(id: String, sequence: Long) {
        call("/v1/customer/support/$id/read", json.encodeToString(ReadThrough(sequence)), authenticated = true)
    }

    fun cachedStatus(): ServiceStatus = try {
        json.decodeFromString<ServiceStatus>(prefs.getString("status", null) ?: "{}").copy(stale = true)
    } catch (_: IllegalArgumentException) {
        ServiceStatus(stale = true)
    }

    suspend fun status(): ServiceStatus = withContext(Dispatchers.IO) {
        try {
            val incidents = json.decodeFromString<List<ServiceIncident>>(call("/v1/service/incidents"))
            ServiceStatus(incidents, System.currentTimeMillis()).also {
                prefs.edit().putString("status", json.encodeToString(it)).apply()
            }
        } catch (_: IOException) {
            cachedStatus()
        } catch (_: IllegalArgumentException) {
            cachedStatus()
        }
    }

    suspend fun register(token: String, serverId: String?) {
        val installation = synchronized(prefs) {
            prefs.getString("installation", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("installation", it).apply()
            }
        }
        call("/v1/service/devices", json.encodeToString(PushDevice(installation, token, serverId)), method = "PUT")
    }

    private suspend fun call(
        path: String, body: String? = null, authenticated: Boolean = false, method: String = "POST"
    ): String = withContext(Dispatchers.IO) {
        if (!configured) throw IOException("Support has no configured server")
        val token = tokens.customerToken()
        if (authenticated && token == null) throw SupportHttpException(401)
        val request = Request.Builder().url(BuildConfig.BILLING_BASE_URL.trimEnd('/') + path)
            .header("Accept", "application/json").header("X-PasarGuard-Client", "native")
        if (token != null && (authenticated || path == "/v1/service/devices")) request.header("Authorization", "Bearer $token")
        if (body != null) request.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use {
            if (!it.isSuccessful) throw SupportHttpException(it.code)
            it.body?.string() ?: throw IOException("Empty response")
        }
    }
}

class SupportHttpException(val status: Int) : IOException("Support request returned HTTP $status")
