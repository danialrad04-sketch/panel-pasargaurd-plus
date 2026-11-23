package com.pasarguard.vpn.data.parser

import com.pasarguard.vpn.domain.model.ProtocolConfig
import com.pasarguard.vpn.domain.model.ProxyProtocol
import com.pasarguard.vpn.domain.model.VpnServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
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

/**
 * Shared proxy link & PasarGuard Xray-JSON subscription parser used by both XrayConfigBuilder
 * and SingBoxConfigBuilder. Written in pure Kotlin/JDK (zero Android framework dependency)
 * so it runs identically on Android API 24+ and JVM unit tests.
 */
@Singleton
class ProxyLinkParser @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a PasarGuard Xray-JSON subscription payload (either a JSON array of full Xray configs
     * or a single Xray config object) returned by `/sub/{token}/xray` or `/sub/{token}/v2ray-json`.
     */
    fun parseXrayJsonSubscription(payload: String): List<VpnServer> {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return emptyList()

        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        val configObjects: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> if (root["outbounds"] != null) listOf(root) else emptyList()
            else -> emptyList()
        }

        return configObjects.mapIndexedNotNull { idx, cfgObj ->
            extractServerFromXrayJson(cfgObj, idx)
        }.distinctBy { it.id }
    }

    private fun extractServerFromXrayJson(cfgObj: JsonObject, index: Int): VpnServer? {
        val outbounds = cfgObj["outbounds"] as? JsonArray ?: return null
        val proxyOutbound = outbounds
            .mapNotNull { it as? JsonObject }
            .firstOrNull { ob ->
                val proto = ob["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                proto in setOf("vless", "vmess", "trojan", "shadowsocks")
            } ?: return null

        val protoStr = proxyOutbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val protocol = when (protoStr) {
            "vless" -> ProxyProtocol.VLESS
            "vmess" -> ProxyProtocol.VMESS
            "trojan" -> ProxyProtocol.TROJAN
            "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
            else -> return null
        }

        val settings = proxyOutbound["settings"] as? JsonObject
        val stream = proxyOutbound["streamSettings"] as? JsonObject

        var address = "panel.xray"
        var port = 443
        var config: ProtocolConfig = ProtocolConfig.Shadowsocks(method = "aes-256-gcm", password = "")

        when (protocol) {
            ProxyProtocol.VLESS -> {
                val vnext = settings?.get("vnext")?.jsonArray?.firstOrNull()?.jsonObject
                address = vnext?.get("address")?.jsonPrimitive?.contentOrNull ?: address
                port = vnext?.get("port")?.jsonPrimitive?.intOrNull ?: port
                val user = vnext?.get("users")?.jsonArray?.firstOrNull()?.jsonObject
                config = ProtocolConfig.Vless(
                    uuid = user?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    flow = user?.get("flow")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    encryption = user?.get("encryption")?.jsonPrimitive?.contentOrNull?.ifBlank { "none" } ?: "none",
                    network = stream?.get("network")?.jsonPrimitive?.contentOrNull ?: "tcp",
                    security = stream?.get("security")?.jsonPrimitive?.contentOrNull ?: "none",
                    sni = stream?.get("realitySettings")?.jsonObject?.get("serverName")?.jsonPrimitive?.contentOrNull
                        ?: stream?.get("tlsSettings")?.jsonObject?.get("serverName")?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }

            ProxyProtocol.VMESS -> {
                val vnext = settings?.get("vnext")?.jsonArray?.firstOrNull()?.jsonObject
                address = vnext?.get("address")?.jsonPrimitive?.contentOrNull ?: address
                port = vnext?.get("port")?.jsonPrimitive?.intOrNull ?: port
                val user = vnext?.get("users")?.jsonArray?.firstOrNull()?.jsonObject
                config = ProtocolConfig.Vmess(
                    uuid = user?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    cipher = user?.get("security")?.jsonPrimitive?.contentOrNull ?: "auto",
                    network = stream?.get("network")?.jsonPrimitive?.contentOrNull ?: "tcp",
                    security = stream?.get("security")?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }

            ProxyProtocol.TROJAN -> {
                val srv = settings?.get("servers")?.jsonArray?.firstOrNull()?.jsonObject
                address = srv?.get("address")?.jsonPrimitive?.contentOrNull ?: address
                port = srv?.get("port")?.jsonPrimitive?.intOrNull ?: port
                config = ProtocolConfig.Trojan(
                    password = srv?.get("password")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    network = stream?.get("network")?.jsonPrimitive?.contentOrNull ?: "tcp",
                    security = stream?.get("security")?.jsonPrimitive?.contentOrNull ?: "tls"
                )
            }

            ProxyProtocol.SHADOWSOCKS -> {
                val srv = settings?.get("servers")?.jsonArray?.firstOrNull()?.jsonObject
                address = srv?.get("address")?.jsonPrimitive?.contentOrNull ?: address
                port = srv?.get("port")?.jsonPrimitive?.intOrNull ?: port
                config = ProtocolConfig.Shadowsocks(
                    method = srv?.get("method")?.jsonPrimitive?.contentOrNull ?: "aes-256-gcm",
                    password = srv?.get("password")?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }

            else -> return null
        }

        val remark = cfgObj["remarks"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: proxyOutbound["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "proxy" }
            ?: "Server ${index + 1} ($address)"

        val rawJsonString = cfgObj.toString()
        val (cc, flag) = detectCountry(remark, address)

        return VpnServer(
            id = stableId("xray-json:$remark:$address:$port:$index"),
            name = remark,
            protocol = protocol,
            address = address,
            port = port,
            countryCode = cc,
            flagEmoji = flag,
            rawUri = "xray-json://$address:$port#$remark",
            config = config,
            rawXrayConfigJson = rawJsonString
        )
    }

    /**
     * Accepts either Base64-encoded subscription content or plain newline-delimited proxy URIs.
     */
    fun parseSubscriptionPayload(payload: String): List<VpnServer> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()

        // If payload is actually an Xray-JSON subscription, parse it directly
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val xrayJsonServers = parseXrayJsonSubscription(trimmed)
            if (xrayJsonServers.isNotEmpty()) return xrayJsonServers
        }

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
        rawUri.startsWith("hysteria2://", ignoreCase = true) ||
            rawUri.startsWith("hy2://", ignoreCase = true) -> parseSingBoxOnly(rawUri, ProxyProtocol.HYSTERIA2)
        rawUri.startsWith("tuic://", ignoreCase = true) -> parseSingBoxOnly(rawUri, ProxyProtocol.TUIC)
        rawUri.startsWith("wireguard://", ignoreCase = true) ||
            rawUri.startsWith("wg://", ignoreCase = true) -> parseSingBoxOnly(rawUri, ProxyProtocol.WIREGUARD)
        else -> null
    }

    private fun parseVless(rawUri: String): VpnServer? = runCatching {
        val parts = splitGenericUri(rawUri) ?: return null
        val uuid = urlDecode(parts.userInfo).takeIf { it.isNotBlank() } ?: return null
        val host = parts.host.takeIf { it.isNotBlank() } ?: return null
        val port = parts.port.takeIf { it in 1..65535 } ?: 443
        val remark = decodeRemark(parts.fragment).ifBlank { "$host:$port" }
        val q = parts.query

        val security = q.firstOf("security")?.lowercase().orEmpty().ifBlank { "none" }
        val network = q.firstOf("type")?.lowercase().orEmpty().ifBlank { "tcp" }
        val alpn = q.firstOf("alpn")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val config = ProtocolConfig.Vless(
            uuid = uuid,
            flow = q.firstOf("flow").orEmpty(),
            encryption = q.firstOf("encryption").orEmpty().ifBlank { "none" },
            network = network,
            security = security,
            sni = q.firstOf("sni", "peer").orEmpty(),
            fingerprint = q.firstOf("fp").orEmpty(),
            publicKey = q.firstOf("pbk").orEmpty(),
            shortId = q.firstOf("sid").orEmpty(),
            spiderX = q.firstOf("spx").orEmpty(),
            pqv = q.firstOf("pqv").orEmpty(),
            path = q.firstOf("path").orEmpty(),
            host = q.firstOf("host").orEmpty(),
            mode = q.firstOf("mode").orEmpty(),
            extraJson = q.firstOf("extra").orEmpty(),
            serviceName = q.firstOf("serviceName", "servicename").orEmpty(),
            authority = q.firstOf("authority").orEmpty(),
            headerType = q.firstOf("headerType", "headertype").orEmpty(),
            seed = q.firstOf("seed").orEmpty(),
            packetEncoding = q.firstOf("packetEncoding", "packetencoding").orEmpty(),
            alpn = alpn,
            allowInsecure = q.firstOf("allowInsecure", "insecure") in setOf("1", "true", "yes")
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
        val body = rawUri.substringAfter("://").trim()
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
            headerType = str("type"),
            serviceName = if (str("net").equals("grpc", ignoreCase = true)) str("path") else "",
            authority = str("authority"),
            seed = str("seed"),
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
        val parts = splitGenericUri(rawUri) ?: return null
        val password = urlDecode(parts.userInfo).takeIf { it.isNotBlank() } ?: return null
        val host = parts.host.takeIf { it.isNotBlank() } ?: return null
        val port = parts.port.takeIf { it in 1..65535 } ?: 443
        val remark = decodeRemark(parts.fragment).ifBlank { "$host:$port" }
        val q = parts.query
        val alpn = q.firstOf("alpn")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val config = ProtocolConfig.Trojan(
            password = password,
            network = q.firstOf("type")?.lowercase().orEmpty().ifBlank { "tcp" },
            security = q.firstOf("security")?.lowercase().orEmpty().ifBlank { "tls" },
            sni = q.firstOf("sni", "peer").orEmpty(),
            path = q.firstOf("path").orEmpty(),
            host = q.firstOf("host").orEmpty(),
            mode = q.firstOf("mode").orEmpty(),
            extraJson = q.firstOf("extra").orEmpty(),
            serviceName = q.firstOf("serviceName", "servicename").orEmpty(),
            authority = q.firstOf("authority").orEmpty(),
            headerType = q.firstOf("headerType", "headertype").orEmpty(),
            seed = q.firstOf("seed").orEmpty(),
            publicKey = q.firstOf("pbk").orEmpty(),
            shortId = q.firstOf("sid").orEmpty(),
            spiderX = q.firstOf("spx").orEmpty(),
            fingerprint = q.firstOf("fp").orEmpty(),
            alpn = alpn,
            allowInsecure = q.firstOf("allowInsecure", "insecure") in setOf("1", "true", "yes")
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
        val withoutScheme = rawUri.substringAfter("://")
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
            val atIndex = authority.lastIndexOf('@')
            val userInfoRaw = authority.substring(0, atIndex)
            val hostPort = authority.substring(atIndex + 1).substringBefore('/')
            val decodedUserInfo = if (userInfoRaw.contains(':')) {
                urlDecode(userInfoRaw)
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
            val decoded = decodeBase64Flexible(authority) ?: return null
            val atIndex = decoded.lastIndexOf('@')
            if (atIndex <= 0) return null
            val creds = decoded.substring(0, atIndex)
            val hostPort = decoded.substring(atIndex + 1).substringBefore('/')
            val colonIdx = creds.indexOf(':')
            if (colonIdx <= 0) return null
            val (h, p) = splitHostPort(hostPort) ?: return null
            Quad(creds.substring(0, colonIdx), creds.substring(colonIdx + 1), h, p)
        }

        val qMap = parseQueryString(query)
        val pluginParam = qMap["plugin"].orEmpty()
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

    private fun parseSingBoxOnly(rawUri: String, protocol: ProxyProtocol): VpnServer? = runCatching {
        val parts = splitGenericUri(rawUri) ?: return null
        val host = parts.host.takeIf { it.isNotBlank() } ?: return null
        val port = parts.port.takeIf { it in 1..65535 } ?: 443
        val remark = decodeRemark(parts.fragment).ifBlank { "$host:$port" }
        val (cc, flag) = detectCountry(remark, host)

        VpnServer(
            id = stableId(rawUri),
            name = remark,
            protocol = protocol,
            address = host,
            port = port,
            countryCode = cc,
            flagEmoji = flag,
            rawUri = rawUri,
            config = ProtocolConfig.SingBoxOnly(
                protocol = protocol,
                params = parts.query + mapOf("userInfo" to urlDecode(parts.userInfo))
            )
        )
    }.getOrNull()

    private data class ParsedUriParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String
    )

    /**
     * Manual RFC-tolerant URI splitter that preserves encoded JSON inside `extra=` or special
     * characters in remarks without throwing on strict `java.net.URI` validation.
     */
    private fun splitGenericUri(rawUri: String): ParsedUriParts? {
        val afterScheme = rawUri.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null

        val hashIdx = afterScheme.indexOf('#')
        val fragment = if (hashIdx >= 0) afterScheme.substring(hashIdx + 1) else ""
        val beforeFragment = if (hashIdx >= 0) afterScheme.substring(0, hashIdx) else afterScheme

        val qIdx = beforeFragment.indexOf('?')
        val queryStr = if (qIdx >= 0) beforeFragment.substring(qIdx + 1) else ""
        val authorityAndPath = if (qIdx >= 0) beforeFragment.substring(0, qIdx) else beforeFragment

        val authority = authorityAndPath.substringBefore('/')
        val atIdx = authority.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority

        val (host, port) = splitHostPort(hostPort) ?: run {
            val cleanHost = hostPort.trim('[', ']')
            if (cleanHost.isEmpty()) return null
            cleanHost to 443
        }

        return ParsedUriParts(
            userInfo = userInfo,
            host = host,
            port = port,
            query = parseQueryString(queryStr),
            fragment = fragment
        )
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val k = urlDecode(pair.substring(0, eqIdx))
                val v = urlDecode(pair.substring(eqIdx + 1))
                result[k] = v
            }
        }
        return result
    }

    private fun Map<String, String>.firstOf(vararg keys: String): String? {
        for (k in keys) {
            val exact = this[k]
            if (!exact.isNullOrEmpty()) return exact
            val ci = entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
            if (!ci.isNullOrEmpty()) return ci
        }
        return null
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        if (hostPort.startsWith("[")) {
            val closeIdx = hostPort.indexOf(']')
            if (closeIdx <= 0) return null
            val ipv6 = hostPort.substring(1, closeIdx)
            val rest = hostPort.substring(closeIdx + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else 443
            return if (port != null) ipv6 to port else null
        }
        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon <= 0) return null
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun containsProxyScheme(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("vless://") ||
            lower.contains("vmess://") ||
            lower.contains("trojan://") ||
            lower.contains("ss://") ||
            lower.contains("hysteria2://") ||
            lower.contains("hy2://") ||
            lower.contains("tuic://") ||
            lower.contains("wireguard://")
    }

    /**
     * Pure-Kotlin RFC 4648 Standard & URL-safe Base64 decoder (works identically on API 24+ and JVM tests).
     */
    fun decodeBase64Flexible(input: String): String? = runCatching {
        val cleaned = input.filter { !it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return null
        val bytes = decodeBase64Bytes(cleaned) ?: return null
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    private fun decodeBase64Bytes(s: String): ByteArray? {
        val table = IntArray(128) { -1 }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { idx, c -> table[c.code] = idx }

        val out = ByteArray((s.length * 3) / 4 + 3)
        var outIdx = 0
        var buf = 0
        var bits = 0
        for (ch in s) {
            if (ch == '=') break
            val code = ch.code
            if (code >= 128 || table[code] == -1) return null
            buf = (buf shl 6) or table[code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIdx++] = ((buf shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outIdx)
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun decodeRemark(fragment: String?): String {
        if (fragment.isNullOrBlank()) return ""
        return runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment).trim()
    }

    private fun stableId(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun detectCountry(remark: String, host: String): Pair<String, String> {
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
        val tokens = remark.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        for ((keywords, pair) in COUNTRY_HINTS) {
            if (pair.first.lowercase() in tokens || keywords.any { haystack.contains(it) }) {
                return pair
            }
        }
        return "GL" to "🌐"
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        private val COUNTRY_HINTS = listOf(
            listOf("germany", "frankfurt", "hetzner", "de1", "de2", "آلمان") to ("DE" to "🇩🇪"),
            listOf("netherlands", "amsterdam", "nl1", "هلند") to ("NL" to "🇳🇱"),
            listOf("finland", "helsinki", "فنلاند") to ("FI" to "🇫🇮"),
            listOf("turkey", "istanbul", "ترکیه") to ("TR" to "🇹🇷"),
            listOf("united states", "usa", "new york", "dallas", "los angeles", "آمریکا") to ("US" to "🇺🇸"),
            listOf("united kingdom", "london", "uk", "انگلیس") to ("GB" to "🇬🇧"),
            listOf("france", "paris", "فرانسه") to ("FR" to "🇫🇷"),
            listOf("sweden", "stockholm", "سوئد") to ("SE" to "🇸🇪"),
            listOf("switzerland", "zurich", "سوئیس") to ("CH" to "🇨🇭"),
            listOf("uae", "dubai", "emirates", "امارات", "دبی") to ("AE" to "🇦🇪"),
            listOf("singapore", "سنگاپور") to ("SG" to "🇸🇬"),
            listOf("japan", "tokyo", "ژاپن") to ("JP" to "🇯🇵"),
            listOf("canada", "toronto", "کانادا") to ("CA" to "🇨🇦"),
            listOf("poland", "warsaw", "لهستان") to ("PL" to "🇵🇱"),
            listOf("austria", "vienna", "اتریش") to ("AT" to "🇦🇹")
        )
    }
}
