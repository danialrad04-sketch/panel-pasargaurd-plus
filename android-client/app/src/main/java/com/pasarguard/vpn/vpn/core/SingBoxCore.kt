package com.pasarguard.vpn.vpn.core

import android.content.Context
import com.pasarguard.vpn.domain.model.VpnEngineType
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.vpn.LatencyTester
import com.pasarguard.vpn.vpn.SingBoxConfigBuilder
import com.pasarguard.vpn.vpn.SingBoxEngineWrapper
import com.pasarguard.vpn.vpn.SpeedSample
import com.pasarguard.vpn.vpn.TunPlatformCallbacks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secondary VPN core powered by `sing-box` (`io.nekohasekai.libbox`), used for protocols
 * Xray-core does not support (Hysteria2, TUIC, WireGuard) or when manually forced in Settings.
 */
@Singleton
class SingBoxCore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configBuilder: SingBoxConfigBuilder,
    private val wrapper: SingBoxEngineWrapper,
    private val latencyTester: LatencyTester
) : VpnCore {

    override val engineType: VpnEngineType = VpnEngineType.SING_BOX

    private val _stats = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 16)
    override val stats: Flow<SpeedSample> = _stats.asSharedFlow()

    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun isAvailable(): Boolean = wrapper.isLibboxAvailable()

    override fun versionInfo(): String =
        if (isAvailable()) "sing-box (libbox)" else "sing-box (libbox.aar not bundled)"

    override suspend fun start(
        server: VpnServer,
        tunFd: Int,
        options: VpnBuildOptions,
        callbacks: TunPlatformCallbacks
    ) = withContext(Dispatchers.IO) {
        if (!wrapper.isLibboxAvailable()) {
            throw IllegalStateException(
                "sing-box engine (libbox.aar) is not bundled in this build. Switch Core to Auto or Xray-core in Settings."
            )
        }

        val configJson = configBuilder.buildConfigJson(
            server = server,
            enableClashApi = true,
            mtu = options.mtu,
            enableIpv6 = options.enableIpv6
        )

        wrapper.start(
            context = context,
            configJson = configJson,
            callbacks = callbacks
        )

        wrapper.startTelemetry(telemetryScope) { sample ->
            _stats.tryEmit(sample)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        wrapper.stop()
    }

    override suspend fun urlTest(server: VpnServer, testUrl: String): Int = withContext(Dispatchers.IO) {
        latencyTester.measureTcpPing(server.address, server.port)
    }
}
