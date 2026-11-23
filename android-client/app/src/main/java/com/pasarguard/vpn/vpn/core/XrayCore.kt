package com.pasarguard.vpn.vpn.core

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.domain.model.VpnEngineType
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.util.SanitizedLogBuffer
import com.pasarguard.vpn.vpn.LatencyTester
import com.pasarguard.vpn.vpn.SpeedSample
import com.pasarguard.vpn.vpn.TunPlatformCallbacks
import com.pasarguard.vpn.vpn.XrayConfigBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primary VPN core powered by native Xray-core v26 (`2dust/AndroidLibXrayLite` / `libv2ray.aar`).
 *
 * Feeds Android's `VpnService` TUN file descriptor directly into Xray-core's native `tun` inbound
 * (`CoreController.startLoop(configJson, tunFd)`), polls Xray's internal outbound traffic stats
 * API (`queryAllOutboundTrafficStats()`) for real-time speed telemetry, and runs true through-core
 * HTTP 204 latency tests via `Libv2ray.measureOutboundDelay(...)`.
 */
@Singleton
class XrayCore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configBuilder: XrayConfigBuilder,
    private val latencyTester: LatencyTester
) : VpnCore {

    override val engineType: VpnEngineType = VpnEngineType.XRAY

    private val _stats = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 16)
    override val stats: Flow<SpeedSample> = _stats.asSharedFlow()

    private val lifecycleMutex = Mutex()
    private val envInitialized = AtomicBoolean(false)
    private val statsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var controller: CoreController? = null
    private var statsJob: Job? = null

    override fun isAvailable(): Boolean = runCatching {
        Libv2ray.checkVersionX().isNotBlank()
    }.getOrDefault(false)

    override fun versionInfo(): String = runCatching {
        Libv2ray.checkVersionX().ifBlank { "Xray-core" }
    }.getOrDefault("Xray-core (unavailable)")

    private fun assetDirectory(): File = File(context.filesDir, "xray_assets").apply { mkdirs() }

    fun hasGeoDataFiles(): Boolean {
        val dir = assetDirectory()
        return File(dir, "geoip.dat").let { it.exists() && it.length() > 0L } &&
            File(dir, "geosite.dat").let { it.exists() && it.length() > 0L }
    }

    private fun ensureCoreEnv() {
        if (envInitialized.compareAndSet(false, true)) {
            val assetDir = assetDirectory()
            extractBundledDatFiles(assetDir)
            runCatching {
                Libv2ray.initCoreEnv(assetDir.absolutePath, "")
            }.onFailure { err ->
                envInitialized.set(false)
                SanitizedLogBuffer.warn(TAG, "initCoreEnv failed: ${err.message}")
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "initCoreEnv failed: ${err.message}")
                }
            }
        }
    }

    private fun extractBundledDatFiles(targetDir: File) {
        val datFiles = listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat")
        for (name in datFiles) {
            val outFile = File(targetDir, name)
            if (outFile.exists() && outFile.length() > 0L) continue
            runCatching {
                context.assets.open(name).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    override suspend fun start(
        server: VpnServer,
        tunFd: Int,
        options: VpnBuildOptions,
        callbacks: TunPlatformCallbacks
    ) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            stopInternal()
            ensureCoreEnv()

            val effectiveOptions = options.copy(geoFilesAvailable = hasGeoDataFiles())
            val configJson = configBuilder.buildConfigJson(
                server = server,
                options = effectiveOptions
            )

            val callbackHandler = object : CoreCallbackHandler {
                override fun startup(): Long {
                    SanitizedLogBuffer.info(TAG, "Xray core started (${versionInfo()})")
                    return 0L
                }

                override fun shutdown(): Long {
                    SanitizedLogBuffer.info(TAG, "Xray core stopped")
                    return 0L
                }

                override fun onEmitStatus(status: Long, msg: String?): Long {
                    if (BuildConfig.DEBUG && !msg.isNullOrBlank()) {
                        Log.d(TAG, "Xray status=$status")
                    }
                    return 0L
                }
            }

            val coreController = Libv2ray.newCoreController(callbackHandler)
                ?: throw IllegalStateException("Failed to create Xray CoreController")

            try {
                coreController.startLoop(configJson, tunFd)
                controller = coreController
                startStatsPolling(coreController)
            } catch (err: Exception) {
                runCatching { coreController.stopLoop() }
                controller = null
                val cleanMsg = sanitizeErrorMessage(err.message)
                SanitizedLogBuffer.error(TAG, "Xray startLoop error: $cleanMsg")
                throw IllegalStateException(
                    "Xray-core failed to start tunnel: $cleanMsg",
                    err
                )
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            stopInternal()
        }
    }

    private fun stopInternal() {
        statsJob?.cancel()
        statsJob = null

        val active = controller
        controller = null
        if (active != null) {
            runCatching {
                if (active.isRunning) {
                    active.stopLoop()
                }
            }
        }
    }

    private fun startStatsPolling(coreController: CoreController) {
        statsJob?.cancel()
        statsJob = statsScope.launch {
            val uid = Process.myUid()
            var prevUidTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            var prevUidRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)

            while (isActive && coreController.isRunning) {
                delay(1_000L)
                val parsed = runCatching {
                    parseOutboundTrafficStats(coreController.queryAllOutboundTrafficStats().orEmpty())
                }.getOrNull()

                val curUidTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val curUidRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val uidUp = (curUidTx - prevUidTx).coerceAtLeast(0L)
                val uidDown = (curUidRx - prevUidRx).coerceAtLeast(0L)
                prevUidTx = curUidTx
                prevUidRx = curUidRx

                val sample = if (parsed != null && (parsed.uploadBytesPerSec > 0L || parsed.downloadBytesPerSec > 0L)) {
                    parsed
                } else {
                    SpeedSample(uploadBytesPerSec = uidUp, downloadBytesPerSec = uidDown)
                }
                _stats.tryEmit(sample)
            }
        }
    }

    internal fun parseOutboundTrafficStats(raw: String): SpeedSample {
        if (raw.isBlank()) return SpeedSample(0L, 0L)
        var uplink = 0L
        var downlink = 0L
        for (entry in raw.split(';')) {
            val parts = entry.split(',')
            if (parts.size < 3) continue
            val tag = parts[0].trim()
            val direction = parts[1].trim().lowercase()
            val bytes = parts[2].trim().toLongOrNull() ?: 0L
            if (tag.equals("block", ignoreCase = true)) continue
            when (direction) {
                "uplink" -> uplink += bytes.coerceAtLeast(0L)
                "downlink" -> downlink += bytes.coerceAtLeast(0L)
            }
        }
        return SpeedSample(uploadBytesPerSec = uplink, downloadBytesPerSec = downlink)
    }

    override suspend fun urlTest(server: VpnServer, testUrl: String): Int = withContext(Dispatchers.IO) {
        ensureCoreEnv()
        val delayMs = runCatching {
            val delayConfig = configBuilder.buildDelayTestConfigJson(server)
            Libv2ray.measureOutboundDelay(delayConfig, testUrl).toInt()
        }.getOrDefault(-1)

        if (delayMs > 0) {
            delayMs
        } else {
            latencyTester.measureTcpPing(server.address, server.port)
        }
    }

    private fun sanitizeErrorMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown core error"
        return SanitizedLogBuffer.sanitize(raw.lineSequence().firstOrNull().orEmpty()).take(180)
            .ifBlank { "Unknown core error" }
    }

    companion object {
        private const val TAG = "XrayCore"
    }
}
