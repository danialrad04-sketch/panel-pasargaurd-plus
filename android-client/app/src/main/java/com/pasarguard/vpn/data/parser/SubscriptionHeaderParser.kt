package com.pasarguard.vpn.data.parser

import com.pasarguard.vpn.domain.model.SubscriptionHeadersMeta
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses standard PasarGuard / Hiddify / V2RayN subscription HTTP response headers:
 * - `subscription-userinfo`
 * - `profile-title` (supports `base64:` prefix)
 * - `support-url`
 * - `profile-web-page-url`
 * - `announce` (supports `base64:` prefix)
 * - `profile-update-interval` (hours)
 */
@Singleton
class SubscriptionHeaderParser @Inject constructor(
    private val linkParser: ProxyLinkParser
) {

    fun parseMetadata(headers: Map<String, String>): SubscriptionHeadersMeta {
        val ci = headers.entries.associate { it.key.lowercase() to it.value.trim() }
        val rawTitle = ci["profile-title"]
        val rawAnnounce = ci["announce"]
        val interval = ci["profile-update-interval"]?.toIntOrNull()?.coerceIn(1, 168)

        return SubscriptionHeadersMeta(
            profileTitle = decodeHeaderText(rawTitle),
            supportUrl = ci["support-url"]?.takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("tg://", ignoreCase = true) },
            profileWebPageUrl = ci["profile-web-page-url"]?.takeIf { it.startsWith("http", ignoreCase = true) },
            announce = decodeHeaderText(rawAnnounce),
            updateIntervalHours = interval
        )
    }

    fun decodeHeaderText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("base64:", ignoreCase = true)) {
            val encoded = trimmed.substringAfter(":").trim()
            return linkParser.decodeBase64Flexible(encoded)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return trimmed
    }
}
