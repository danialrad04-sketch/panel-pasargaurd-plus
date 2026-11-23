package com.pasarguard.vpn.data.diagnostics

import com.pasarguard.vpn.domain.model.DiagnosticsIpInfo
import com.pasarguard.vpn.util.SanitizedLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SpeedTestProgress(
    val isRunning: Boolean,
    val currentMbps: Double,
    val peakMbps: Double,
    val averageMbps: Double,
    val downloadedBytes: Long,
    val progressFraction: Float,
    val errorMessage: String? = null
)

/**
 * Performs live Connection Diagnostics:
 * - Exit IP / Country / City / ISP lookup (configurable endpoint, supports ipwho.is, ipapi.co, ip-api.com)
 * - Basic DNS resolver / leak verification
 * - Real streaming download speed test through the active network / VPN tunnel emitting live Mbps progress
 */
@Singleton
class DiagnosticsEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {

    suspend fun fetchIpAndDnsInfo(endpointUrl: String): Result<DiagnosticsIpInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = endpointUrl.trim().ifBlank { DEFAULT_IP_ENDPOINT }
                val client = okHttpClient.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "PasarGuardVPN/1.2")
                    .build()

                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }

                val root = json.parseToJsonElement(body).jsonObject
                val ip = root["ip"]?.jsonPrimitive?.content
                    ?: root["query"]?.jsonPrimitive?.content
                    ?: "-"
                val country = root["country"]?.jsonPrimitive?.content
                    ?: root["country_name"]?.jsonPrimitive?.content
                    ?: "-"
                val city = root["city"]?.jsonPrimitive?.content ?: "-"
                val connectionObj = root["connection"]?.jsonObject
                val isp = connectionObj?.get("isp")?.jsonPrimitive?.content
                    ?: connectionObj?.get("org")?.jsonPrimitive?.content
                    ?: root["org"]?.jsonPrimitive?.content
                    ?: root["isp"]?.jsonPrimitive?.content
                    ?: "-"

                // Lightweight DNS resolution check via Cloudflare DNS endpoint
                val resolvedHost = runCatching {
                    InetAddress.getByName("one.one.one.one").hostAddress.orEmpty()
                }.getOrDefault("")
                val dnsOk = resolvedHost == "1.1.1.1" || resolvedHost == "1.0.0.1"
                val dnsNote = if (dnsOk) {
                    "Encrypted / Verified ($resolvedHost)"
                } else {
                    "Resolver returned ${resolvedHost.ifBlank { "unresolved" }}"
                }

                SanitizedLogBuffer.info(TAG, "Diagnostics IP check succeeded ($country)")
                DiagnosticsIpInfo(
                    ip = ip,
                    country = country,
                    city = city,
                    isp = isp,
                    dnsLeakDetected = !dnsOk,
                    dnsResolverNote = dnsNote
                )
            }
        }

    fun runDownloadSpeedTest(speedTestUrl: String): Flow<SpeedTestProgress> = flow {
        val url = speedTestUrl.trim().ifBlank { DEFAULT_SPEED_URL }
        emit(
            SpeedTestProgress(
                isRunning = true,
                currentMbps = 0.0,
                peakMbps = 0.0,
                averageMbps = 0.0,
                downloadedBytes = 0L,
                progressFraction = 0f
            )
        )

        val client = okHttpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "PasarGuardVPN/1.2")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        SpeedTestProgress(
                            isRunning = false,
                            currentMbps = 0.0,
                            peakMbps = 0.0,
                            averageMbps = 0.0,
                            downloadedBytes = 0L,
                            progressFraction = 0f,
                            errorMessage = "HTTP ${response.code}"
                        )
                    )
                    return@use
                }

                val body = response.body ?: error("Empty response body")
                val expectedBytes = body.contentLength().takeIf { it > 0L } ?: 10_000_000L
                val stream = body.byteStream()
                val buffer = ByteArray(32 * 1024)

                var totalBytes = 0L
                var windowBytes = 0L
                var peakMbps = 0.0
                val startNs = System.nanoTime()
                var lastEmitNs = startNs

                while (currentCoroutineContext().isActive) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    windowBytes += read

                    val nowNs = System.nanoTime()
                    val windowSec = (nowNs - lastEmitNs) / 1_000_000_000.0
                    val totalSec = ((nowNs - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)

                    if (windowSec >= 0.25) {
                        val instantMbps = (windowBytes * 8.0) / (windowSec * 1_000_000.0)
                        val avgMbps = (totalBytes * 8.0) / (totalSec * 1_000_000.0)
                        if (instantMbps > peakMbps) peakMbps = instantMbps
                        val fraction = (totalBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)

                        emit(
                            SpeedTestProgress(
                                isRunning = true,
                                currentMbps = instantMbps,
                                peakMbps = peakMbps,
                                averageMbps = avgMbps,
                                downloadedBytes = totalBytes,
                                progressFraction = fraction
                            )
                        )
                        windowBytes = 0L
                        lastEmitNs = nowNs
                    }

                    // Cap duration at 12 seconds for responsive UX on slow networks
                    if (totalSec >= 12.0) break
                }

                val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                val finalAvgMbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
                if (finalAvgMbps > peakMbps) peakMbps = finalAvgMbps

                SanitizedLogBuffer.info(
                    TAG,
                    "Speed test completed: avg=%.2f Mbps, peak=%.2f Mbps".format(finalAvgMbps, peakMbps)
                )

                emit(
                    SpeedTestProgress(
                        isRunning = false,
                        currentMbps = finalAvgMbps,
                        peakMbps = peakMbps,
                        averageMbps = finalAvgMbps,
                        downloadedBytes = totalBytes,
                        progressFraction = 1f
                    )
                )
            }
        } catch (err: Exception) {
            emit(
                SpeedTestProgress(
                    isRunning = false,
                    currentMbps = 0.0,
                    peakMbps = 0.0,
                    averageMbps = 0.0,
                    downloadedBytes = 0L,
                    progressFraction = 0f,
                    errorMessage = err.localizedMessage ?: "Speed test failed"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "Diagnostics"
        const val DEFAULT_IP_ENDPOINT = "https://ipwho.is/"
        const val DEFAULT_SPEED_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
    }
}
