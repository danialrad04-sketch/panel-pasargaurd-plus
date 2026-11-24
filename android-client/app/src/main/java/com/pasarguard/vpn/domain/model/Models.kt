package com.pasarguard.vpn.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

enum class SubscriptionStatus {
    ACTIVE,
    ON_HOLD,
    LIMITED,
    EXPIRED,
    DISABLED,
    UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): SubscriptionStatus = when (raw?.lowercase()) {
            "active" -> ACTIVE
            "on_hold" -> ON_HOLD
            "limited" -> LIMITED
            "expired" -> EXPIRED
            "disabled" -> DISABLED
            else -> UNKNOWN
        }
    }
}

data class SubscriptionCredentials(
    val baseUrl: String,
    val token: String
) {
    val fullSubscriptionUrl: String
        get() = "${baseUrl.trimEnd('/')}/sub/$token"
}

data class SubscriptionHeadersMeta(
    val profileTitle: String? = null,
    val supportUrl: String? = null,
    val profileWebPageUrl: String? = null,
    val announce: String? = null,
    val updateIntervalHours: Int? = null
)

data class SubscriptionProfile(
    val id: String,
    val name: String,
    val subscriptionUrl: String,
    val colorHex: Long = 0xFF06B6D4L,
    val isActive: Boolean = false,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
    val updateIntervalHours: Int? = null,
    val lastSyncedEpochMs: Long = 0L
)

data class SubscriptionInfo(
    val username: String,
    val status: SubscriptionStatus,
    val usedTrafficBytes: Long,
    val lifetimeUsedTrafficBytes: Long,
    val dataLimitBytes: Long?, // null or <= 0 means unlimited
    val resetStrategy: String,
    val expireEpochSeconds: Long?, // null means never expires
    val onHoldDurationSeconds: Long?,
    val hwidLimit: Int?,
    val subscriptionUrl: String,
    val subUpdatedAt: String?,
    val lastSyncedEpochMs: Long,
    val profileTitle: String? = null,
    val supportUrl: String? = null,
    val profileWebPageUrl: String? = null,
    val announce: String? = null,
    val suggestedUpdateIntervalHours: Int? = null
) {
    val isUnlimitedTraffic: Boolean
        get() = dataLimitBytes == null || dataLimitBytes <= 0L

    val remainingTrafficBytes: Long?
        get() = dataLimitBytes?.takeIf { it > 0L }?.let { max(0L, it - usedTrafficBytes) }

    val remainingTrafficFraction: Float
        get() {
            val limit = dataLimitBytes?.takeIf { it > 0L } ?: return 1f
            val remaining = max(0L, limit - usedTrafficBytes)
            return (remaining.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    fun remainingSeconds(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Long? {
        if (status == SubscriptionStatus.ON_HOLD && onHoldDurationSeconds != null) {
            return onHoldDurationSeconds
        }
        val exp = expireEpochSeconds ?: return null
        return max(0L, exp - nowEpochSeconds)
    }

    fun remainingDays(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Int? {
        val secs = remainingSeconds(nowEpochSeconds) ?: return null
        return (secs / 86_400L).toInt()
    }

    fun remainingTimeFraction(
        referenceCycleDays: Int = 30,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Float {
        val secs = remainingSeconds(nowEpochSeconds) ?: return 1f
        val cycleSecs = referenceCycleDays * 86_400.0
        return (secs / cycleSecs).toFloat().coerceIn(0f, 1f)
    }
}

enum class ProxyProtocol(val label: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    HYSTERIA2("Hysteria2"),
    TUIC("TUIC"),
    WIREGUARD("WireGuard")
}

enum class VpnEngineType(val label: String) {
    XRAY("Xray-core"),
    SING_BOX("sing-box")
}

enum class CorePreference {
    AUTO,
    XRAY,
    SING_BOX
}

enum class SubscriptionFormatStrategy {
    PREFER_XRAY_JSON,
    LINKS_ONLY
}

enum class PingTestMode {
    THROUGH_CORE,
    TCP,
    MULTI_URL
}

enum class AutoSelectMode {
    LOWEST_PING,
    MOST_STABLE,
    ROUND_ROBIN_TOP_N
}

enum class ServerSortMode {
    PING,
    STABILITY,
    NAME,
    COUNTRY,
    FAVORITES
}

enum class StabilityTier {
    EXCELLENT,
    GOOD,
    POOR,
    DEAD,
    UNTESTED
}

data class PingQualityMetrics(
    val avgMs: Int,
    val minMs: Int,
    val maxMs: Int,
    val jitterMs: Int,
    val packetLossPct: Int,
    val stabilityScore: Int,
    val tier: StabilityTier,
    val samples: List<Int> = emptyList()
)

data class PingProgressState(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0
)

data class PingHistoryPoint(
    val serverId: String,
    val latencyMs: Int,
    val timestampMs: Long
)

enum class RoutingMode {
    GLOBAL,
    BYPASS_LAN,
    BYPASS_IRAN,
    CUSTOM
}

enum class RoutingRuleMatchType {
    DOMAIN,
    IP,
    GEOSITE,
    GEOIP
}

enum class RoutingRuleAction {
    DIRECT,
    PROXY,
    BLOCK
}

@Serializable
data class CustomRoutingRule(
    val id: String,
    val matchType: RoutingRuleMatchType,
    val value: String,
    val action: RoutingRuleAction
)

enum class DnsPreset(val label: String, val address: String) {
    CLOUDFLARE_DOH("Cloudflare DoH", "https+local://1.1.1.1/dns-query"),
    GOOGLE_DOH("Google DoH", "https+local://8.8.8.8/dns-query"),
    QUAD9_DOH("Quad9 DoH", "https+local://9.9.9.9/dns-query"),
    SYSTEM("System DNS", "1.1.1.1"),
    CUSTOM("Custom", "")
}

enum class CalendarSystem {
    JALALI,
    GREGORIAN
}

enum class NetworkAutoConnectRule {
    NONE,
    UNTRUSTED_WIFI,
    MOBILE_DATA,
    ALWAYS
}

sealed class ProtocolConfig {
    data class Vless(
        val uuid: String,
        val flow: String = "",
        val encryption: String = "none",
        val network: String = "tcp",
        val security: String = "none",
        val sni: String = "",
        val fingerprint: String = "",
        val publicKey: String = "",
        val shortId: String = "",
        val spiderX: String = "",
        val pqv: String = "",
        val path: String = "",
        val host: String = "",
        val mode: String = "",
        val extraJson: String = "",
        val serviceName: String = "",
        val authority: String = "",
        val headerType: String = "",
        val seed: String = "",
        val packetEncoding: String = "",
        val alpn: List<String> = emptyList(),
        val allowInsecure: Boolean = false
    ) : ProtocolConfig()

    data class Vmess(
        val uuid: String,
        val alterId: Int = 0,
        val cipher: String = "auto",
        val network: String = "tcp",
        val security: String = "",
        val sni: String = "",
        val path: String = "",
        val host: String = "",
        val headerType: String = "",
        val serviceName: String = "",
        val authority: String = "",
        val seed: String = "",
        val fingerprint: String = "",
        val alpn: List<String> = emptyList(),
        val allowInsecure: Boolean = false
    ) : ProtocolConfig()

    data class Trojan(
        val password: String,
        val network: String = "tcp",
        val security: String = "tls",
        val sni: String = "",
        val path: String = "",
        val host: String = "",
        val mode: String = "",
        val extraJson: String = "",
        val serviceName: String = "",
        val authority: String = "",
        val headerType: String = "",
        val seed: String = "",
        val publicKey: String = "",
        val shortId: String = "",
        val spiderX: String = "",
        val fingerprint: String = "",
        val alpn: List<String> = emptyList(),
        val allowInsecure: Boolean = false
    ) : ProtocolConfig()

    data class Shadowsocks(
        val method: String,
        val password: String,
        val plugin: String = "",
        val pluginOpts: String = ""
    ) : ProtocolConfig()

    data class SingBoxOnly(
        val protocol: ProxyProtocol,
        val params: Map<String, String> = emptyMap()
    ) : ProtocolConfig()
}

data class VpnServer(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val address: String,
    val port: Int,
    val countryCode: String,
    val flagEmoji: String,
    val rawUri: String,
    val pingMs: Int? = null, // null = untested, -1 = timeout/unreachable
    val minPingMs: Int? = null,
    val maxPingMs: Int? = null,
    val jitterMs: Int = 0,
    val packetLossPct: Int = 0,
    val stabilityScore: Int = 0,
    val isFavorite: Boolean = false,
    val groupName: String = "Subscription",
    val config: ProtocolConfig,
    val rawXrayConfigJson: String? = null
) {
    val transportLabel: String
        get() = when (val c = config) {
            is ProtocolConfig.Vless -> c.network.uppercase()
            is ProtocolConfig.Vmess -> c.network.uppercase()
            is ProtocolConfig.Trojan -> c.network.uppercase()
            is ProtocolConfig.Shadowsocks -> "TCP/UDP"
            is ProtocolConfig.SingBoxOnly -> c.protocol.label
        }

    val securityLabel: String
        get() = when (val c = config) {
            is ProtocolConfig.Vless -> c.security.uppercase()
            is ProtocolConfig.Vmess -> c.security.ifBlank { "NONE" }.uppercase()
            is ProtocolConfig.Trojan -> c.security.ifBlank { "TLS" }.uppercase()
            is ProtocolConfig.Shadowsocks -> c.method
            is ProtocolConfig.SingBoxOnly -> "TLS/QUIC"
        }

    val stabilityTier: StabilityTier
        get() = when {
            pingMs == null -> StabilityTier.UNTESTED
            pingMs <= 0 || packetLossPct >= 100 -> StabilityTier.DEAD
            stabilityScore >= 80 -> StabilityTier.EXCELLENT
            stabilityScore >= 55 -> StabilityTier.GOOD
            stabilityScore > 0 -> StabilityTier.POOR
            pingMs < 150 -> StabilityTier.EXCELLENT
            pingMs < 320 -> StabilityTier.GOOD
            else -> StabilityTier.POOR
        }
}

data class UsagePoint(
    val periodStart: String,
    val bytes: Long
)

data class SessionStatRecord(
    val id: Long = 0L,
    val serverId: String,
    val serverName: String,
    val engineLabel: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val dayKey: String // YYYY-MM-DD
)

data class DiagnosticsIpInfo(
    val ip: String,
    val country: String,
    val city: String,
    val isp: String,
    val dnsLeakDetected: Boolean = false,
    val dnsResolverNote: String = ""
)

@Serializable
data class AppUpdateInfo(
    @SerialName("latest_version") val latestVersion: String,
    @SerialName("latest_version_code") val latestVersionCode: Int,
    @SerialName("download_url") val downloadUrl: String,
    val changelog: String = "",
    val sha256: String? = null
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnSessionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeServer: VpnServer? = null,
    val activeEngine: VpnEngineType = VpnEngineType.XRAY,
    val isAutoMode: Boolean = true,
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val connectedSinceEpochMs: Long? = null,
    val errorMessage: String? = null
)

data class RenewalPlan(
    val id: String,
    val titleEn: String,
    val titleFa: String,
    val durationDays: Int,
    val dataLimitGb: Int?, // null = unlimited
    val priceDisplayEn: String,
    val priceDisplayFa: String,
    val isPopular: Boolean = false,
    val badgeEn: String? = null,
    val badgeFa: String? = null
)

data class CheckoutSession(
    val referenceId: String,
    val paymentUrl: String,
    val planId: String
)

enum class AppLanguage(val code: String) {
    PERSIAN("fa"),
    ENGLISH("en");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: PERSIAN
    }
}

enum class ThemePalette { OCEAN, JADE, DUSK }

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

enum class SplitTunnelMode {
    DISABLED,
    BYPASS_SELECTED,
    ONLY_SELECTED
}

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val coachTipsDismissed: Boolean = false,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val persianDigitsEnabled: Boolean = true,
    val calendarSystem: CalendarSystem = CalendarSystem.JALALI,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val themePalette: ThemePalette = ThemePalette.OCEAN,
    val dynamicColorEnabled: Boolean = false,

    // Core & Subscription
    val corePreference: CorePreference = CorePreference.AUTO,
    val subscriptionFormatStrategy: SubscriptionFormatStrategy = SubscriptionFormatStrategy.PREFER_XRAY_JSON,
    val autoRefreshIntervalHours: Int = 6, // 0 = manual, 1 / 6 / 12 / 24
    val dismissedAnnouncementHash: String = "",

    // Ping & Auto-select (Stage 2A A1)
    val pingTestMode: PingTestMode = PingTestMode.THROUGH_CORE,
    val pingTestUrl: String = PingScoring.DEFAULT_TEST_URLS.first(),
    val customPingUrls: List<String> = emptyList(),
    val pingSamplesPerServer: Int = 2, // 1..5
    val pingTimeoutSec: Int = 5, // 2..10
    val pingMaxConcurrency: Int = 5, // 1..10
    val autoSelectMode: AutoSelectMode = AutoSelectMode.LOWEST_PING,
    val autoTestOnAppOpen: Boolean = false,
    val autoTestBeforeAutoSelect: Boolean = true,
    val hideDeadServers: Boolean = false,
    val serverSortMode: ServerSortMode = ServerSortMode.PING,

    // Diagnostics endpoints
    val ipInfoEndpoint: String = "https://ipwho.is/",
    val speedTestUrl: String = "https://speed.cloudflare.com/__down?bytes=10000000",
    val updateCheckUrl: String = "",

    // Connection, Routing, DNS & Advanced Xray (Stage 2A A3)
    val mtu: Int = 1500,
    val enableIpv6: Boolean = false,
    val enableFragment: Boolean = false,
    val enableMux: Boolean = false,
    val enableSniffing: Boolean = true,
    val allowInsecureGlobal: Boolean = false,
    val routingMode: RoutingMode = RoutingMode.BYPASS_LAN,
    val customRoutingRules: List<CustomRoutingRule> = emptyList(),
    val dnsPreset: DnsPreset = DnsPreset.CLOUDFLARE_DOH,
    val customDnsServer: String = "",
    val remoteDnsThroughProxy: Boolean = true,
    val autoConnect: Boolean = false,
    val networkAutoConnectRule: NetworkAutoConnectRule = NetworkAutoConnectRule.NONE,
    val autoReconnect: Boolean = true,
    val autoFailover: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val blockTrafficOnDrop: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val splitTunnelPackages: Set<String> = emptySet(),
    val selectedServerId: String? = null, // null = Auto

    // Privacy & Security
    val appLockEnabled: Boolean = false,
    val appLockPin: String = "",
    val hideInRecents: Boolean = false,
    val disguiseNotification: Boolean = false,

    // Notifications
    val expiryWarningEnabled: Boolean = true,
    val lowTrafficWarningEnabled: Boolean = true
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)
