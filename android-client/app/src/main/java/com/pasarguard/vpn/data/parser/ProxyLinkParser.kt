package com.pasarguard.vpn.data.parser

import android.net.Uri
import android.util.Base64
import com.pasarguard.vpn.domain.model.ProtocolConfig
import com.pasarguard.vpn.domain.model.ProxyProtocol
import com.pasarguard.vpn.domain.model.VpnServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class SubscriptionHeaderUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long? = null,
    val expire: Long? = null
)

@Singleton
class ProxyLinkParser @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Accepts either Base64-encoded subscription content or plain newline-delimited proxy URIs.
     */
    fun parseSubscriptionPayload(payload: String): List<VpnServer> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()

        val decodedText = if (containsProxyScheme(trimmed)) {
            trimmed
        } else {
            decodeBase64Flexible(trimmed) ?: trimmed
        }

        val lines = decodedText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        return parseLinks(lines)
    }

    fun parseLinks(links: List<String>): List<VpnServer> {
        return links.mapNotNull { raw -> parseSingleUri(raw.trim()) }
            .distinctBy { it.id }
    }

    fun parseSubscriptionUserInfoHeader(headerValue: String?): SubscriptionHeaderUserInfo? {
        if (headerValue.isNullOrBlank()) return null
        val pairs = headerValue.split(";").mapNotNull { segment ->
            val parts = segment.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim().toLongOrNull() else null
        }.toMap()

        if (pairs.isEmpty()) return null
        val totalRaw = pairs["total"]
        return SubscriptionHeaderUserInfo(
            upload = pairs["upload"] ?: 0L,
            download = pairs["download"] ?: 0L,
            total = totalRaw?.takeIf { it > 0L },
            expire = pairs["expire"]?.takeIf { it > 0L }
        )
    }

    fun parseSingleUri(rawUri: String): VpnServer? = when {
        rawUri.startsWith("vless://", ignoreCase = true) -> parseVless(rawUri)
        rawUri.startsWith("vmess://", ignoreCase = true) -> parseVmess(rawUri)
        rawUri.startsWith("trojan://", ignoreCase = true) -> parseTrojan(rawUri)
        rawUri.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(rawUri)
        else -> null
    }

    private fun parseVless(rawUri: String): VpnServer? = runCatching {
        val uri = Uri.parse(rawUri)
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val remark = decodeRemark(uri.fragment).ifBlank { "$host:$port" }

        val security = uri.getQueryParameter("security")?.lowercase().orEmpty().ifBlank { "none" }
        val network = uri.getQueryParameter("type")?.lowercase().orEmpty().ifBlank { "tcp" }
        val alpn = uri.getQueryParameter("alpn")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val config = ProtocolConfig.Vless(
            uuid = uuid,
            flow = uri.getQueryParameter("flow").orEmpty(),
            encryption = uri.getQueryParameter("encryption").orEmpty().ifBlank { "none" },
            network = network,
            security = security,
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer").orEmpty(),
            fingerprint = uri.getQueryParameter("fp").orEmpty().ifBlank { "chrome" },
            publicKey = uri.getQueryParameter("pbk").orEmpty(),
            shortId = uri.getQueryParameter("sid").orEmpty(),
            path = uri.getQueryParameter("path").orEmpty(),
            host = uri.getQueryParameter("host").orEmpty(),
            serviceName = uri.getQueryParameter("serviceName").orEmpty(),
            alpn = alpn,
            allowInsecure = uri.getQueryParameter("allowInsecure") == "1" ||
                uri.getQueryParameter("allowInsecure").equals("true", ignoreCase = true)
        )

        val (cc, flag) = detectCountry(remark, host)
        VpnServer(
            id = stableId(rawUri),
            name = remark,
            protocol = ProxyProtocol.VLESS,
            address = host,
            port = port,
            countryCode = cc,
            flagEmoji = flag,
            rawUri = rawUri,
            config = config
        )
    }.getOrNull()

    private fun parseVmess(rawUri: String): VpnServer? = runCatching {
        val body = rawUri.removePrefix("vmess://").trim()
        val decodedJson = decodeBase64Flexible(body) ?: return null
        val obj = json.parseToJsonElement(decodedJson) as? JsonObject ?: return null

        fun str(key: String): String = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        fun intVal(key: String, default: Int): Int = obj[key]?.jsonPrimitive?.intOrNull
            ?: obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: default

        val host = str("add").ifBlank { return null }
        val port = intVal("port", 443)
        val uuid = str("id").ifBlank { return null }
        val remark = str("ps").ifBlank { "$host:$port" }
        val tls = str("tls").lowercase()
        val alpn = str("alpn").split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val config = ProtocolConfig.Vmess(
            uuid = uuid,
            alterId = intVal("aid", 0),
            cipher = str("scy").ifBlank { "auto" },
            network = str("net").lowercase().ifBlank { "tcp" },
            security = tls,
            sni = str("sni").ifBlank { str("host") },
            path = str("path"),
            host = str("host"),
            fingerprint = str("fp"),
            alpn = alpn,
            allowInsecure = str("insecure") == "1" || str("allowInsecure") == "true"
        )

        val (cc, flag) = detectCountry(remark, host)
        VpnServer(
            id = stableId(rawUri),
            name = remark,
            protocol = ProxyProtocol.VMESS,
            address = host,
            port = port,
            countryCode = cc,
            flagEmoji = flag,
            rawUri = rawUri,
            config = config
        )
    }.getOrNull()

    private fun parseTrojan(rawUri: String): VpnServer? = runCatching {
        val uri = Uri.parse(rawUri)
        val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val remark = decodeRemark(uri.fragment).ifBlank { "$host:$port" }
        val alpn = uri.getQueryParameter("alpn")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val config = ProtocolConfig.Trojan(
            password = URLDecoder.decode(password, "UTF-8"),
            network = uri.getQueryParameter("type")?.lowercase().orEmpty().ifBlank { "tcp" },
            security = uri.getQueryParameter("security")?.lowercase().orEmpty().ifBlank { "tls" },
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer").orEmpty(),
            path = uri.getQueryParameter("path").orEmpty(),
            host = uri.getQueryParameter("host").orEmpty(),
            serviceName = uri.getQueryParameter("serviceName").orEmpty(),
            fingerprint = uri.getQueryParameter("fp").orEmpty().ifBlank { "chrome" },
            alpn = alpn,
            allowInsecure = uri.getQueryParameter("allowInsecure") == "1" ||
                uri.getQueryParameter("allowInsecure").equals("true", ignoreCase = true)
        )

        val (cc, flag) = detectCountry(remark, host)
        VpnServer(
            id = stableId(rawUri),
            name = remark,
            protocol = ProxyProtocol.TROJAN,
            address = host,
            port = port,
            countryCode = cc,
            flagEmoji = flag,
            rawUri = rawUri,
            config = config
        )
    }.getOrNull()

    private fun parseShadowsocks(rawUri: String): VpnServer? = runCatching {
        val withoutScheme = rawUri.removePrefix("ss://")
        val fragmentIndex = withoutScheme.indexOf('#')
        val remark = if (fragmentIndex >= 0) {
            decodeRemark(withoutScheme.substring(fragmentIndex + 1))
        } else {
            ""
        }
        val corePart = if (fragmentIndex >= 0) withoutScheme.substring(0, fragmentIndex) else withoutScheme
        val queryIndex = corePart.indexOf('?')
        val query = if (queryIndex >= 0) corePart.substring(queryIndex + 1) else ""
        val authority = if (queryIndex >= 0) corePart.substring(0, queryIndex) else corePart

        val (method, password, host, port) = if (authority.contains('@')) {
            // SIP002: base64(method:password)@host:port or plain method:password@host:port
            val atIndex = authority.lastIndexOf('@')
            val userInfoRaw = authority.substring(0, atIndex)
            val hostPort = authority.substring(atIndex + 1)
            val decodedUserInfo = if (userInfoRaw.contains(':')) {
                URLDecoder.decode(userInfoRaw, "UTF-8")
            } else {
                decodeBase64Flexible(userInfoRaw) ?: return null
            }
            val colonIdx = decodedUserInfo.indexOf(':')
            if (colonIdx <= 0) return null
            val (h, p) = splitHostPort(hostPort) ?: return null
            Quad(
                decodedUserInfo.substring(0, colonIdx),
                decodedUserInfo.substring(colonIdx + 1),
                h,
                p
            )
        } else {
            // Legacy: base64(method:password@host:port)
            val decoded = decodeBase64Flexible(authority) ?: return null
            val atIndex = decoded.lastIndexOf('@')
            if (atIndex <= 0) return null
            val creds = decoded.substring(0, atIndex)
            val hostPort = decoded.substring(atIndex + 1)
            val colonIdx = creds.indexOf(':')
            if (colonIdx <= 0) return null
            val (h, p) = splitHostPort(hostPort) ?: return null
            Quad(creds.substring(0, colonIdx), creds.substring(colonIdx + 1), h, p)
        }

        val pluginParam = query.split("&")
            .firstOrNull { it.startsWith("plugin=") }
            ?.removePrefix("plugin=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            .orEmpty()
        val pluginName = pluginParam.substringBefore(";", "")
        val pluginOpts = pluginParam.substringAfter(";", "")

        val finalRemark = remark.ifBlank { "$host:$port" }
        val (cc, flag) = detectCountry(finalRemark, host)
        VpnServer(
            id = stableId(rawUri),
            name = finalRemark,
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            countryCode = cc,
            flagEmoji = flag,
            rawUri = rawUri,
            config = ProtocolConfig.Shadowsocks(
                method = method,
                password = password,
                plugin = pluginName,
                pluginOpts = pluginOpts
            )
        )
    }.getOrNull()

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon <= 0) return null
        val host = hostPort.substring(0, lastColon).trim('[', ']')
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun containsProxyScheme(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("vless://") ||
            lower.contains("vmess://") ||
            lower.contains("trojan://") ||
            lower.contains("ss://")
    }

    private fun decodeBase64Flexible(input: String): String? = runCatching {
        val normalized = input.trim().replace("-", "+").replace("_", "/")
        val padded = normalized.padEnd(((normalized.length + 3) / 4) * 4, '=')
        String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun decodeRemark(fragment: String?): String {
        if (fragment.isNullOrBlank()) return ""
        return runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment).trim()
    }

    private fun stableId(rawUri: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(rawUri.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun detectCountry(remark: String, host: String): Pair<String, String> {
        // Check if remark already contains a Unicode regional indicator flag pair
        val codePoints = remark.codePoints().toArray()
        for (i in 0 until codePoints.size - 1) {
            val a = codePoints[i]
            val b = codePoints[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val c1 = ('A'.code + (a - 0x1F1E6)).toChar()
                val c2 = ('A'.code + (b - 0x1F1E6)).toChar()
                val flag = String(intArrayOf(a, b), 0, 2)
                return "$c1$c2" to flag
            }
        }

        val haystack = "$remark $host".lowercase()
        for ((keywords, pair) in COUNTRY_HINTS) {
            if (keywords.any { haystack.contains(it) }) return pair
        }
        return "GL" to "🌐"
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        private val COUNTRY_HINTS = listOf(
            listOf("germany", "frankfurt", "hetzner", " de ", "-de-", "de1", "de2", "آلمان") to ("DE" to "🇩🇪"),
            listOf("netherlands", "amsterdam", " nl ", "-nl-", "nl1", "هلند") to ("NL" to "🇳🇱"),
            listOf("finland", "helsinki", " fi ", "-fi-", "فنلاند") to ("FI" to "🇫🇮"),
            listOf("turkey", "istanbul", " tr ", "-tr-", "ترکیه") to ("TR" to "🇹🇷"),
            listOf("united states", "usa", "new york", "dallas", "los angeles", " us ", "-us-", "آمریکا") to ("US" to "🇺🇸"),
            listOf("united kingdom", "london", " uk ", " gb ", "-uk-", "انگلیس") to ("GB" to "🇬🇧"),
            listOf("france", "paris", " fr ", "-fr-", "فرانسه") to ("FR" to "🇫🇷"),
            listOf("sweden", "stockholm", " se ", "-se-", "سوئد") to ("SE" to "🇸🇪"),
            listOf("switzerland", "zurich", " ch ", "-ch-", "سوئیس") to ("CH" to "🇨🇭"),
            listOf("uae", "dubai", "emirates", " ae ", "امارات", "دبی") to ("AE" to "🇦🇪"),
            listOf("singapore", " sg ", "-sg-", "سنگاپور") to ("SG" to "🇸🇬"),
            listOf("japan", "tokyo", " jp ", "-jp-", "ژاپن") to ("JP" to "🇯🇵"),
            listOf("canada", "toronto", " ca ", "-ca-", "کانادا") to ("CA" to "🇨🇦"),
            listOf("poland", "warsaw", " pl ", "-pl-", "لهستان") to ("PL" to "🇵🇱"),
            listOf("austria", "vienna", " at ", "-at-", "اتریش") to ("AT" to "🇦🇹")
        )
    }
}
