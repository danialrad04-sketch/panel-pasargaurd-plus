package com.pasarguard.vpn.vpn

import android.content.Context
import android.net.TrafficStats
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.pasarguard.vpn.BrandConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SpeedSample(
    val uploadBytesPerSec: Long,
    val downloadBytesPerSec: Long
)

interface TunPlatformCallbacks {
    fun protectSocket(fd: Int): Boolean
    fun openTunInterface(): ParcelFileDescriptor?
}

/**
 * Production wrapper for `sing-box` (`io.nekohasekai.libbox`).
 *
 * Uses dynamic interface binding to `io.nekohasekai.libbox.Libbox`, `BoxService`, and
 * `PlatformInterface` so the project compiles cleanly before `libbox.aar` is dropped into
 * `app/libs/` and immediately runs the full native Go/TUN engine once `libbox.aar` is present.
 */
@Singleton
class SingBoxEngineWrapper @Inject constructor(
    private val json: Json
) {
    private var activeBoxService: Any? = null
    private var activeTunFd: ParcelFileDescriptor? = null
    private var telemetryJob: Job? = null

    private val localHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Streaming Clash /traffic endpoint
        .build()

    fun isLibboxAvailable(): Boolean = runCatching {
        Class.forName("io.nekohasekai.libbox.Libbox")
        true
    }.getOrDefault(false)

    /**
     * Initializes `libbox` working directories, builds a native `BoxService` from the generated
     * sing-box JSON config, binds Android's `VpnService` TUN descriptor and `protect(fd)` callbacks,
     * and starts the encrypted tunnel.
     */
    @Throws(Exception::class)
    fun start(
        context: Context,
        configJson: String,
        callbacks: TunPlatformCallbacks
    ) {
        stop()

        val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
        val workDir = File(context.filesDir, "singbox_work").apply { mkdirs() }
        val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }

        if (!isLibboxAvailable()) {
            // Establish Android TUN interface cleanly and surface actionable guidance if AAR is missing
            activeTunFd = callbacks.openTunInterface()
                ?: throw IllegalStateException("Failed to open Android TUN interface")
            Log.w(
                TAG,
                "libbox.aar not found in app/libs/. TUN interface opened in standby; add libbox.aar to enable packet forwarding."
            )
            return
        }

        val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
        val setupOptionsClass = Class.forName("io.nekohasekai.libbox.SetupOptions")
        val setupOptions = setupOptionsClass.getDeclaredConstructor().newInstance().apply {
            setupOptionsClass.getMethod("setBasePath", String::class.java).invoke(this, baseDir.absolutePath)
            setupOptionsClass.getMethod("setWorkingPath", String::class.java).invoke(this, workDir.absolutePath)
            setupOptionsClass.getMethod("setTempPath", String::class.java).invoke(this, tempDir.absolutePath)
        }
        libboxClass.getMethod("setup", setupOptionsClass).invoke(null, setupOptions)

        val platformInterfaceClass = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        val platformProxy = Proxy.newProxyInstance(
            platformInterfaceClass.classLoader,
            arrayOf(platformInterfaceClass)
        ) { _, method, args ->
            when (method.name) {
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                    if (fd >= 0) callbacks.protectSocket(fd)
                    null
                }

                "openTun" -> {
                    val pfd = callbacks.openTunInterface()
                        ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
                    activeTunFd = pfd
                    pfd.fd
                }

                "usePlatformAutoDetectInterfaceControl" -> true
                "useProcFS" -> false
                "underNetworkExtension" -> false
                "includeAllNetworks" -> false
                "writeLog" -> {
                    val msg = args?.firstOrNull()?.toString().orEmpty()
                    Log.d(TAG, msg)
                    null
                }

                else -> defaultReturnValue(method.returnType)
            }
        }

        val newServiceMethod = libboxClass.getMethod(
            "newService",
            String::class.java,
            platformInterfaceClass
        )
        val service = newServiceMethod.invoke(null, configJson, platformProxy)
            ?: throw IllegalStateException("Libbox.newService returned null")
        service.javaClass.getMethod("start").invoke(service)
        activeBoxService = service
    }

    /**
     * Streams live upload/download speed from sing-box's local Clash API (`127.0.0.1:9090/traffic`),
     * automatically falling back to Android UID `TrafficStats` delta sampling if Clash API isn't reachable.
     */
    fun startTelemetry(scope: CoroutineScope, onSample: (SpeedSample) -> Unit) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            if (isLibboxAvailable()) {
                val clashWorked = runCatching {
                    streamClashApiTraffic(onSample)
                }.isSuccess
                if (clashWorked && !isActive) return@launch
            }

            // Fallback: Android UID TrafficStats 1-second delta sampling
            val uid = Process.myUid()
            var prevTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            var prevRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            while (isActive) {
                delay(1_000L)
                val curTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val curRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val up = (curTx - prevTx).coerceAtLeast(0L)
                val down = (curRx - prevRx).coerceAtLeast(0L)
                prevTx = curTx
                prevRx = curRx
                onSample(SpeedSample(uploadBytesPerSec = up, downloadBytesPerSec = down))
            }
        }
    }

    private suspend fun streamClashApiTraffic(onSample: (SpeedSample) -> Unit) {
        delay(600L)
        val request = Request.Builder()
            .url("http://127.0.0.1:${BrandConfig.clashApiPort}/traffic")
            .build()
        localHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Clash API status ${response.code}")
            val stream = response.body?.byteStream() ?: return
            BufferedReader(InputStreamReader(stream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    runCatching {
                        val obj = json.parseToJsonElement(line).jsonObject
                        val up = obj["up"]?.jsonPrimitive?.longOrNull ?: 0L
                        val down = obj["down"]?.jsonPrimitive?.longOrNull ?: 0L
                        onSample(SpeedSample(uploadBytesPerSec = up, downloadBytesPerSec = down))
                    }
                }
            }
        }
    }

    fun stop() {
        telemetryJob?.cancel()
        telemetryJob = null

        activeBoxService?.let { svc ->
            runCatching { svc.javaClass.getMethod("close").invoke(svc) }
        }
        activeBoxService = null

        activeTunFd?.let { fd ->
            runCatching { fd.close() }
        }
        activeTunFd = null
    }

    private fun defaultReturnValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        String::class.java -> ""
        else -> null
    }

    companion object {
        private const val TAG = "SingBoxEngine"
    }
}
