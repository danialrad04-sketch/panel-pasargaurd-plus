package com.pasarguard.vpn.domain.model

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
    val lastSyncedEpochMs: Long
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
    SHADOWSOCKS("Shadowsocks")
}

sealed class ProtocolConfig {
    data class Vless(
        val uuid: String,
        val flow: String = "",
        val encryption: String = "none",
        val network: String = "tcp",
        val security: String = "none",
        val sni: String = "",
        val fingerprint: String = "chrome",
        val publicKey: String = "",
        val shortId: String = "",
        val path: String = "",
        val host: String = "",
        val serviceName: String = "",
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
        val serviceName: String = "",
        val fingerprint: String = "chrome",
        val alpn: List<String> = emptyList(),
        val allowInsecure: Boolean = false
    ) : ProtocolConfig()

    data class Shadowsocks(
        val method: String,
        val password: String,
        val plugin: String = "",
        val pluginOpts: String = ""
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
    val isFavorite: Boolean = false,
    val config: ProtocolConfig
)

data class UsagePoint(
    val periodStart: String,
    val bytes: Long
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
    val language: AppLanguage = AppLanguage.PERSIAN,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val autoConnect: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val splitTunnelPackages: Set<String> = emptySet(),
    val selectedServerId: String? = null, // null = Auto (Best Ping)
    val expiryWarningEnabled: Boolean = true,
    val lowTrafficWarningEnabled: Boolean = true
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)
