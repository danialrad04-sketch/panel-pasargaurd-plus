package com.pasarguard.vpn.data.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.pasarguard.vpn.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches PasarGuard-compatible client identity & HWID headers to subscription requests
 * so panels with `hwid_limit` enabled accurately recognize this device.
 */
@Singleton
class HwidHeaderInterceptor @Inject constructor(
    @ApplicationContext private val context: Context
) : Interceptor {

    private val hashedHwid: String by lazy {
        @SuppressLint("HardwareIds")
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "${Build.MANUFACTURER}-${Build.MODEL}"
        val digest = MessageDigest.getInstance("SHA-256").digest("pg-hwid:$raw".toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "${BuildConfig.BRAND_NAME.replace(" ", "")}/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; sing-box)")
            .header("X-HWID", hashedHwid)
            .header("X-Device-OS", "Android")
            .header("X-Ver-OS", Build.VERSION.RELEASE ?: "14")
            .header("X-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .build()
        return chain.proceed(request)
    }
}

/**
 * Retries transient network errors and HTTP 502/503/504 responses with bounded exponential backoff.
 */
class ExponentialRetryInterceptor(
    private val maxRetries: Int = 2,
    private val initialDelayMs: Long = 600L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null

        for (attempt in 0..maxRetries) {
            try {
                val response = chain.proceed(request)
                if (response.code !in listOf(502, 503, 504) || attempt == maxRetries) {
                    return response
                }
                response.close()
            } catch (ioe: IOException) {
                lastException = ioe
                if (attempt == maxRetries) throw ioe
            }
            try {
                Thread.sleep(initialDelayMs * (1L shl attempt))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        throw lastException ?: IOException("Request failed after retries")
    }
}
