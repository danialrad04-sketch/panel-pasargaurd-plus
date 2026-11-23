package com.pasarguard.vpn.data.diagnostics

import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.domain.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks a configurable JSON update feed (`{ latestVersion, latestVersionCode, downloadUrl, changelog }`)
 * and returns [AppUpdateInfo] when a newer version is available.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    suspend fun checkForUpdate(endpointUrl: String): Result<AppUpdateInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = endpointUrl.trim()
                if (url.isBlank()) return@runCatching null

                val client = okHttpClient.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
                val info = json.decodeFromString<AppUpdateInfo>(body)
                val hasNewerCode = info.latestVersionCode > BuildConfig.VERSION_CODE
                val hasNewerName = info.latestVersionCode == 0 &&
                    info.latestVersion.isNotBlank() &&
                    info.latestVersion != BuildConfig.VERSION_NAME
                if (hasNewerCode || hasNewerName) info else null
            }
        }
}
