package com.pasarguard.vpn.vpn.core

import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.CorePreference
import com.pasarguard.vpn.domain.model.CustomRoutingRule
import com.pasarguard.vpn.domain.model.DnsPreset
import com.pasarguard.vpn.domain.model.ProtocolConfig
import com.pasarguard.vpn.domain.model.ProxyProtocol
import com.pasarguard.vpn.domain.model.RoutingMode
import com.pasarguard.vpn.domain.model.VpnEngineType
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.vpn.SpeedSample
import com.pasarguard.vpn.vpn.TunPlatformCallbacks
import com.pasarguard.vpn.vpn.XrayConfigBuilder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full runtime engine options passed from [AppSettings] to [VpnCore] implementations.
 */
data class VpnBuildOptions(
    val mtu: Int = XrayConfigBuilder.DEFAULT_MTU,
    val enableIpv6: Boolean = false,
    val routingMode: RoutingMode = RoutingMode.BYPASS_LAN,
    val customRoutingRules: List<CustomRoutingRule> = emptyList(),
    val dnsPreset: DnsPreset = DnsPreset.CLOUDFLARE_DOH,
    val customDnsServer: String = "",
    val remoteDnsThroughProxy: Boolean = true,
    val enableFragment: Boolean = false,
    val enableMux: Boolean = false,
    val enableSniffing: Boolean = true,
    val allowInsecureGlobal: Boolean = false,
    val geoFilesAvailable: Boolean = false
) {
    companion object {
        fun fromSettings(
            settings: AppSettings,
            geoFilesAvailable: Boolean = false
        ): VpnBuildOptions = VpnBuildOptions(
            mtu = settings.mtu.coerceIn(1200, 9000),
            enableIpv6 = settings.enableIpv6,
            routingMode = settings.routingMode,
            customRoutingRules = settings.customRoutingRules,
            dnsPreset = settings.dnsPreset,
            customDnsServer = settings.customDnsServer,
            remoteDnsThroughProxy = settings.remoteDnsThroughProxy,
            enableFragment = settings.enableFragment,
            enableMux = settings.enableMux,
            enableSniffing = settings.enableSniffing,
            allowInsecureGlobal = settings.allowInsecureGlobal,
            geoFilesAvailable = geoFilesAvailable
        )
    }
}

/**
 * Multi-core VPN engine contract implemented by [XrayCore] (primary) and [SingBoxCore] (secondary).
 */
interface VpnCore {
    val engineType: VpnEngineType

    /** Live upload/download byte-per-second telemetry stream while the tunnel is running. */
    val stats: Flow<SpeedSample>

    /** Starts the tunnel engine bound to the Android VpnService TUN file descriptor. */
    @Throws(Exception::class)
    suspend fun start(
        server: VpnServer,
        tunFd: Int,
        options: VpnBuildOptions,
        callbacks: TunPlatformCallbacks
    )

    /** Cleanly stops the tunnel engine and releases native sockets/resources. */
    suspend fun stop()

    /**
     * Real through-core HTTP 204 latency test (not just TCP handshake).
     * Returns round-trip delay in milliseconds, or `-1` on failure/timeout.
     */
    suspend fun urlTest(
        server: VpnServer,
        testUrl: String = DEFAULT_TEST_URL
    ): Int

    /** Human-readable core version string (e.g. `Xray-core v26.x`). */
    fun versionInfo(): String

    /** Returns true if the native Go/JNI binding for this core is bundled in the APK. */
    fun isAvailable(): Boolean

    companion object {
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
    }
}

sealed class CoreSelectionResult {
    data class Selected(
        val engine: VpnEngineType,
        val warning: String? = null
    ) : CoreSelectionResult()

    data class Incompatible(
        val requestedEngine: VpnEngineType,
        val reason: String
    ) : CoreSelectionResult()
}

/**
 * Automatic and user-override core selector:
 * - Default -> Xray-core (VLESS, VMess, Trojan, Shadowsocks: xhttp/splithttp, reality, vision,
 *   VLESS encryption / post-quantum, httpupgrade, grpc, ws, tcp, kcp, and panel Xray-JSON).
 * - Secondary -> sing-box only for protocols Xray-core does not support (Hysteria2, TUIC, WireGuard)
 *   or when explicitly forced by the user for a compatible config.
 */
@Singleton
class CoreSelector @Inject constructor() {

    fun select(
        server: VpnServer,
        preference: CorePreference
    ): CoreSelectionResult {
        val singBoxOnlyProtocols = setOf(
            ProxyProtocol.HYSTERIA2,
            ProxyProtocol.TUIC,
            ProxyProtocol.WIREGUARD
        )

        if (server.protocol in singBoxOnlyProtocols || server.config is ProtocolConfig.SingBoxOnly) {
            return when (preference) {
                CorePreference.XRAY -> CoreSelectionResult.Incompatible(
                    requestedEngine = VpnEngineType.XRAY,
                    reason = "${server.protocol.label} is not supported by Xray-core. Switch Core to Auto or sing-box in Settings."
                )

                CorePreference.AUTO,
                CorePreference.SING_BOX -> CoreSelectionResult.Selected(VpnEngineType.SING_BOX)
            }
        }

        val xrayOnlyFeature = detectXrayOnlyFeature(server)

        return when (preference) {
            CorePreference.AUTO -> CoreSelectionResult.Selected(VpnEngineType.XRAY)

            CorePreference.XRAY -> CoreSelectionResult.Selected(VpnEngineType.XRAY)

            CorePreference.SING_BOX -> {
                if (xrayOnlyFeature != null) {
                    CoreSelectionResult.Incompatible(
                        requestedEngine = VpnEngineType.SING_BOX,
                        reason = "This config uses $xrayOnlyFeature which sing-box cannot run. Switch Core to Auto or Xray-core in Settings."
                    )
                } else {
                    CoreSelectionResult.Selected(VpnEngineType.SING_BOX)
                }
            }
        }
    }

    /**
     * Returns a concise human-readable description of any Xray-exclusive feature present in [server],
     * or `null` if the config is also runnable on sing-box.
     */
    fun detectXrayOnlyFeature(server: VpnServer): String? {
        if (!server.rawXrayConfigJson.isNullOrBlank()) {
            return "a full Xray-JSON panel profile"
        }

        return when (val cfg = server.config) {
            is ProtocolConfig.Vless -> {
                val net = cfg.network.lowercase()
                when {
                    net == "xhttp" || net == "splithttp" -> "XHTTP/SplitHTTP transport"
                    net == "kcp" || net == "mkcp" -> "mKCP transport"
                    cfg.encryption.isNotBlank() && !cfg.encryption.equals("none", ignoreCase = true) ->
                        "VLESS Encryption (${cfg.encryption.take(20)})"
                    cfg.pqv.isNotBlank() -> "post-quantum Reality ML-DSA-65 verification (pqv)"
                    else -> null
                }
            }

            is ProtocolConfig.Trojan -> {
                val net = cfg.network.lowercase()
                when {
                    net == "xhttp" || net == "splithttp" -> "XHTTP/SplitHTTP transport"
                    net == "kcp" || net == "mkcp" -> "mKCP transport"
                    else -> null
                }
            }

            is ProtocolConfig.Vmess -> {
                val net = cfg.network.lowercase()
                if (net == "kcp" || net == "mkcp") "mKCP transport" else null
            }

            is ProtocolConfig.Shadowsocks -> null
            is ProtocolConfig.SingBoxOnly -> null
        }
    }
}
