package com.pasarguard.vpn.data.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.domain.model.AppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()
    private val prefs = context.getSharedPreferences("app-release", Context.MODE_PRIVATE)

    val isConfigured: Boolean get() = BuildConfig.BILLING_BASE_URL.isNotBlank()

    fun autoDownloadEnabled(): Boolean = prefs.getBoolean("download_automatically", false)

    fun setAutoDownload(enabled: Boolean) {
        prefs.edit().putBoolean("download_automatically", enabled).apply()
    }

    fun shouldCheckAutomatically(): Boolean {
        val elapsed = System.currentTimeMillis() - prefs.getLong("checked_at", 0)
        return isConfigured && (elapsed < 0 || elapsed >= AUTO_INTERVAL_MS)
    }

    fun canPrompt(info: AppUpdateInfo): Boolean {
        if (prefs.getInt("dismissed_build", 0) != info.latestVersionCode) return true
        val elapsed = System.currentTimeMillis() - prefs.getLong("dismissed_at", 0)
        return elapsed < 0 || elapsed >= REMINDER_INTERVAL_MS
    }

    fun dismiss(info: AppUpdateInfo) {
        prefs.edit().putInt("dismissed_build", info.latestVersionCode)
            .putLong("dismissed_at", System.currentTimeMillis()).apply()
    }

    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        check(isConfigured)
        val origin = BuildConfig.BILLING_BASE_URL.toHttpUrl()
        val endpoint = origin.newBuilder().addPathSegments("v1/app/config")
            .addQueryParameter("platform", "android")
            .addQueryParameter("package_name", context.packageName)
            .addQueryParameter("version_code", BuildConfig.VERSION_CODE.toString())
            .addQueryParameter("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .build()
        val request = Request.Builder().url(endpoint).header("Accept", "application/json").build()
        val info = client.newCall(request).execute().use { response ->
            check(response.request.url.host == origin.host && response.request.url.port == origin.port &&
                response.request.url.isHttps && response.isSuccessful)
            if (response.code == 204) return@use null
            val source = checkNotNull(response.body).source()
            check(!source.request(65_537)) { "Update record is too large" }
            json.decodeFromString<AppUpdateInfo>(source.readUtf8())
        }
        ensureActive()
        prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
        info?.takeIf {
            it.latestVersionCode > BuildConfig.VERSION_CODE &&
                it.downloadUrl.toHttpUrl().isHttps && it.sha256?.matches(SHA256) == true
        }
    }

    suspend fun download(info: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        require(info.latestVersionCode > BuildConfig.VERSION_CODE && info.sha256?.matches(SHA256) == true)
        val url = info.downloadUrl.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        val directory = File(context.cacheDir, "app-updates")
        check(directory.isDirectory || directory.mkdirs())
        val partial = File.createTempFile("pasarguard-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful && response.request.url.isHttps)
                val body = checkNotNull(response.body)
                check(body.contentLength() <= BYTE_LIMIT)
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var length = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            length += read
                            check(length <= BYTE_LIMIT)
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        check(length > 0)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == info.sha256)
            validatePackage(partial, info.latestVersionCode)
            directory.listFiles()?.filter { it != partial }?.forEach { it.delete() }
            partial
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(file: File, expectedBuild: Int) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val incoming = checkNotNull(manager.getPackageArchiveInfo(file.absolutePath, flags))
        val installed = manager.getPackageInfo(context.packageName, flags)
        check(incoming.packageName == installed.packageName)
        check(PackageInfoCompat.getLongVersionCode(incoming) == expectedBuild.toLong())
        val currentSigners = signatures(installed)
        check(currentSigners.isNotEmpty() && signatures(incoming) == currentSigners)
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<Signature> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        else info.signatures.orEmpty().toSet()

    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.startActivity(permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val installer = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(installer)
        return true
    }

    private companion object {
        const val BYTE_LIMIT = 150L * 1024 * 1024
        const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val REMINDER_INTERVAL_MS = 24 * 60 * 60 * 1000L
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
