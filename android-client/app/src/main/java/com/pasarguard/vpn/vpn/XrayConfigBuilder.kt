package com.pasarguard.vpn.vpn

import com.pasarguard.vpn.domain.model.CustomRoutingRule
import com.pasarguard.vpn.domain.model.DnsPreset
import com.pasarguard.vpn.domain.model.ProtocolConfig
import com.pasarguard.vpn.domain.model.RoutingMode
import com.pasarguard.vpn.domain.model.RoutingRuleAction
import com.pasarguard.vpn.domain.model.RoutingRuleMatchType
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.vpn.core.VpnBuildOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates complete, production-valid Xray-core v26 JSON configurations:
 * - Native Android TUN inbound (`protocol: "tun"`) consuming `xray.tun.fd` from VpnService
 * - Full VLESS support: Reality (pbk/sid/spx/fp), XTLS-Vision flow, XHTTP/SplitHTTP (mode + parsed `extra` JSON),
 *   post-quantum VLESS `encryption` (e.g. `mlkem768x25519plus...`) & `pqv`, gRPC (`serviceName`/`authority`),
 *   WebSocket, HTTPUpgrade, mKCP (`headerType`/`seed`), raw/TCP HTTP header spoofing
 * - Full VMess, Trojan, and Shadowsocks support
 * - Routing modes: Global, Bypass LAN, Bypass Iran (with automatic `.ir` + Iranian services fallback when
 *   external `geoip.dat`/`geosite.dat` are absent), and Custom rules
 * - Configurable DNS presets (Cloudflare/Google/Quad9 DoH, System, Custom) + remote DNS through proxy
 * - Optional TLS Fragment (`dialerProxy` -> freedom fragment outbound), Mux (auto-disabled for Vision flow),
 *   Sniffing toggle, and global AllowInsecure override
 * - Panel Xray-JSON passthrough (`preparePanelXrayJsonForAndroidTun`) preserving panel-configured
 *   fragment, noise, dialerProxy, mux, and custom routing rules verbatim
 */
@Singleton
class XrayConfigBuilder @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildConfigJson(
        server: VpnServer,
        mtu: Int = DEFAULT_MTU,
        enableIpv6: Boolean = false
    ): String = buildConfigJson(
        server = server,
        options = VpnBuildOptions(mtu = mtu, enableIpv6 = enableIpv6)
    )

    fun buildConfigJson(
        server: VpnServer,
        options: VpnBuildOptions
    ): String {
        if (!server.rawXrayConfigJson.isNullOrBlank()) {
            val prepared = preparePanelXrayJsonForAndroidTun(server.rawXrayConfigJson, options)
            if (prepared != null) return prepared
        }

        val proxyOutbound = buildProxyOutbound(server, options)
            ?: throw IllegalArgumentException("Xray-core does not support protocol: ${server.protocol.label}")

        val root = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", "warning")
            }

            putJsonObject("stats") {}

            putJsonObject("policy") {
                putJsonObject("levels") {
                    putJsonObject("8") {
                        put("connIdle", 300)
                        put("downlinkOnly", 1)
                        put("handshake", 4)
                        put("uplinkOnly", 1)
                    }
                }
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }

            put("dns", buildDnsConfig(options))

            putJsonArray("inbounds") {
                add(buildTunInbound(options.mtu, options.enableSniffing))
                add(buildLocalSocksInbound(options.enableSniffing))
            }

            putJsonArray("outbounds") {
                add(proxyOutbound)
                if (options.enableFragment) {
                    add(buildFragmentFreedomOutbound(options.enableIpv6))
                }
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        put("domainStrategy", if (options.enableIpv6) "UseIP" else "UseIPv4")
                    }
                })
                add(buildJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                    putJsonObject("settings") {
                        putJsonObject("response") { put("type", "http") }
                    }
                })
                add(buildJsonObject {
                    put("tag", "dns-out")
                    put("protocol", "dns")
                })
            }

            put("routing", buildRoutingConfig(options))
        }
        return root.toString()
    }

    /**
     * Minimal outbound-only Xray config for `Libv2ray.measureOutboundDelay(config, url)`
     * so through-core real latency tests work even while another VPN session is active.
     */
    fun buildDelayTestConfigJson(
        server: VpnServer,
        allowInsecureGlobal: Boolean = false
    ): String {
        if (!server.rawXrayConfigJson.isNullOrBlank()) {
            return server.rawXrayConfigJson
        }
        val proxyOutbound = buildProxyOutbound(
            server = server,
            options = VpnBuildOptions(allowInsecureGlobal = allowInsecureGlobal)
        ) ?: throw IllegalArgumentException("Xray-core does not support protocol: ${server.protocol.label}")

        return buildJsonObject {
            putJsonObject("log") { put("loglevel", "none") }
            putJsonArray("outbounds") {
                add(proxyOutbound)
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
            }
        }.toString()
    }

    fun preparePanelXrayJsonForAndroidTun(
        rawPanelJson: String,
        mtu: Int = DEFAULT_MTU,
        enableIpv6: Boolean = false
    ): String? = preparePanelXrayJsonForAndroidTun(
        rawPanelJson = rawPanelJson,
        options = VpnBuildOptions(mtu = mtu, enableIpv6 = enableIpv6)
    )

    fun preparePanelXrayJsonForAndroidTun(
        rawPanelJson: String,
        options: VpnBuildOptions
    ): String? = runCatching {
        val original = json.parseToJsonElement(rawPanelJson).jsonObject
        val mutable = LinkedHashMap<String, JsonElement>(original)

        if (!mutable.containsKey("stats")) {
            mutable["stats"] = buildJsonObject {}
        }
        val existingPolicy = mutable["policy"] as? JsonObject
        val policyMap = LinkedHashMap<String, JsonElement>(existingPolicy ?: emptyMap())
        val existingSystem = policyMap["system"] as? JsonObject
        val systemMap = LinkedHashMap<String, JsonElement>(existingSystem ?: emptyMap())
        systemMap["statsOutboundUplink"] = JsonPrimitive(true)
        systemMap["statsOutboundDownlink"] = JsonPrimitive(true)
        policyMap["system"] = JsonObject(systemMap)
        mutable["policy"] = JsonObject(policyMap)

        mutable["inbounds"] = buildJsonArray {
            add(buildTunInbound(options.mtu, options.enableSniffing))
            add(buildLocalSocksInbound(options.enableSniffing))
        }

        if (!mutable.containsKey("dns")) {
            mutable["dns"] = buildDnsConfig(options)
        }
        if (!mutable.containsKey("routing")) {
            mutable["routing"] = buildRoutingConfig(options)
        }

        mutable["log"] = buildJsonObject { put("loglevel", "warning") }

        JsonObject(mutable).toString()
    }.getOrNull()

    private fun buildTunInbound(mtu: Int, enableSniffing: Boolean): JsonObject = buildJsonObject {
        put("tag", "tun-in")
        put("protocol", "tun")
        putJsonObject("settings") {
            put("name", "xray0")
            put("MTU", mtu)
            put("userLevel", 8)
        }
        putJsonObject("sniffing") {
            put("enabled", enableSniffing)
            putJsonArray("destOverride") {
                add(JsonPrimitive("http"))
                add(JsonPrimitive("tls"))
                add(JsonPrimitive("quic"))
            }
            put("routeOnly", false)
        }
    }

    private fun buildLocalSocksInbound(enableSniffing: Boolean): JsonObject = buildJsonObject {
        put("tag", "socks-in")
        put("listen", "127.0.0.1")
        put("port", LOCAL_SOCKS_PORT)
        put("protocol", "socks")
        putJsonObject("settings") {
            put("auth", "noauth")
            put("udp", true)
            put("userLevel", 8)
        }
        putJsonObject("sniffing") {
            put("enabled", enableSniffing)
            putJsonArray("destOverride") {
                add(JsonPrimitive("http"))
                add(JsonPrimitive("tls"))
                add(JsonPrimitive("quic"))
            }
        }
    }

    private fun buildFragmentFreedomOutbound(enableIpv6: Boolean): JsonObject = buildJsonObject {
        put("tag", "fragment")
        put("protocol", "freedom")
        putJsonObject("settings") {
            put("domainStrategy", if (enableIpv6) "UseIP" else "UseIPv4")
            putJsonObject("fragment") {
                put("packets", "tlshello")
                put("length", "100-200")
                put("interval", "10-20")
            }
        }
        putJsonObject("streamSettings") {
            putJsonObject("sockopt") {
                put("tcpNoDelay", true)
            }
        }
    }

    private fun buildDnsConfig(options: VpnBuildOptions): JsonObject {
        val rawPrimary = when (options.dnsPreset) {
            DnsPreset.CUSTOM -> options.customDnsServer.trim().ifBlank { DnsPreset.CLOUDFLARE_DOH.address }
            else -> options.dnsPreset.address
        }
        val primary = if (options.remoteDnsThroughProxy && rawPrimary.startsWith("https+local://")) {
            rawPrimary.replace("https+local://", "https://")
        } else {
            rawPrimary
        }

        return buildJsonObject {
            put("queryStrategy", if (options.enableIpv6) "UseIP" else "UseIPv4")
            putJsonArray("servers") {
                add(JsonPrimitive(primary))
                add(JsonPrimitive("1.1.1.1"))
                add(JsonPrimitive("8.8.8.8"))
            }
        }
    }

    private fun buildRoutingConfig(options: VpnBuildOptions): JsonObject = buildJsonObject {
        put("domainStrategy", "IPIfNonMatch")
        putJsonArray("rules") {
            // Hijack DNS queries from TUN into Xray's built-in DNS engine
            add(buildJsonObject {
                put("type", "field")
                put("port", "53")
                put("inboundTag", buildJsonArray { add(JsonPrimitive("tun-in")) })
                put("outboundTag", "dns-out")
            })

            if (options.routingMode != RoutingMode.GLOBAL) {
                // Bypass LAN, loopback, and private subnets directly
                add(buildJsonObject {
                    put("type", "field")
                    putJsonArray("ip") {
                        LAN_CIDRS.forEach { add(JsonPrimitive(it)) }
                    }
                    put("outboundTag", "direct")
                })
            }

            if (options.routingMode == RoutingMode.BYPASS_IRAN) {
                add(buildJsonObject {
                    put("type", "field")
                    putJsonArray("domain") {
                        if (options.geoFilesAvailable) {
                            add(JsonPrimitive("geosite:category-ir"))
                        }
                        IRAN_DIRECT_DOMAINS.forEach { add(JsonPrimitive(it)) }
                    }
                    put("outboundTag", "direct")
                })
                if (options.geoFilesAvailable) {
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("ip") {
                            add(JsonPrimitive("geoip:ir"))
                        }
                        put("outboundTag", "direct")
                    })
                }
            }

            if (options.routingMode == RoutingMode.CUSTOM && options.customRoutingRules.isNotEmpty()) {
                options.customRoutingRules.forEach { rule ->
                    buildCustomRuleJson(rule, options.geoFilesAvailable)?.let { add(it) }
                }
            }
        }
    }

    private fun buildCustomRuleJson(
        rule: CustomRoutingRule,
        geoFilesAvailable: Boolean
    ): JsonObject? {
        val value = rule.value.trim()
        if (value.isBlank()) return null
        val outboundTag = when (rule.action) {
            RoutingRuleAction.DIRECT -> "direct"
            RoutingRuleAction.PROXY -> "proxy"
            RoutingRuleAction.BLOCK -> "block"
        }
        return when (rule.matchType) {
            RoutingRuleMatchType.DOMAIN -> buildJsonObject {
                put("type", "field")
                putJsonArray("domain") {
                    val normalized = if (value.contains(":")) value else "domain:$value"
                    add(JsonPrimitive(normalized))
                }
                put("outboundTag", outboundTag)
            }

            RoutingRuleMatchType.IP -> buildJsonObject {
                put("type", "field")
                putJsonArray("ip") { add(JsonPrimitive(value)) }
                put("outboundTag", outboundTag)
            }

            RoutingRuleMatchType.GEOSITE -> {
                val clean = value.removePrefix("geosite:")
                if (!geoFilesAvailable) {
                    // Safe fallback so Xray never fails to start when geosite.dat is not bundled
                    if (clean.contains("ir", ignoreCase = true)) {
                        buildJsonObject {
                            put("type", "field")
                            putJsonArray("domain") { add(JsonPrimitive("domain:ir")) }
                            put("outboundTag", outboundTag)
                        }
                    } else null
                } else {
                    buildJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { add(JsonPrimitive("geosite:$clean")) }
                        put("outboundTag", outboundTag)
                    }
                }
            }

            RoutingRuleMatchType.GEOIP -> {
                if (!geoFilesAvailable) return null
                val clean = value.removePrefix("geoip:")
                buildJsonObject {
                    put("type", "field")
                    putJsonArray("ip") { add(JsonPrimitive("geoip:$clean")) }
                    put("outboundTag", outboundTag)
                }
            }
        }
    }

    private fun buildProxyOutbound(server: VpnServer, options: VpnBuildOptions): JsonObject? =
        when (val cfg = server.config) {
            is ProtocolConfig.Vless -> buildVlessOutbound(server, cfg, options)
            is ProtocolConfig.Vmess -> buildVmessOutbound(server, cfg, options)
            is ProtocolConfig.Trojan -> buildTrojanOutbound(server, cfg, options)
            is ProtocolConfig.Shadowsocks -> buildShadowsocksOutbound(server, cfg, options)
            is ProtocolConfig.SingBoxOnly -> null
        }

    private fun buildVlessOutbound(
        server: VpnServer,
        cfg: ProtocolConfig.Vless,
        options: VpnBuildOptions
    ): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("protocol", "vless")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    putJsonArray("users") {
                        add(buildJsonObject {
                            put("id", cfg.uuid)
                            put("encryption", cfg.encryption.ifBlank { "none" })
                            if (cfg.flow.isNotBlank()) put("flow", cfg.flow)
                            if (cfg.pqv.isNotBlank()) put("pqv", cfg.pqv)
                            put("level", 8)
                        })
                    }
                })
            }
        }
        put(
            "streamSettings",
            buildStreamSettings(
                network = cfg.network,
                security = cfg.security,
                serverAddress = server.address,
                sni = cfg.sni,
                fingerprint = cfg.fingerprint,
                alpn = cfg.alpn,
                allowInsecure = cfg.allowInsecure || options.allowInsecureGlobal,
                publicKey = cfg.publicKey,
                shortId = cfg.shortId,
                spiderX = cfg.spiderX,
                pqv = cfg.pqv,
                path = cfg.path,
                host = cfg.host,
                mode = cfg.mode,
                extraJson = cfg.extraJson,
                serviceName = cfg.serviceName,
                authority = cfg.authority,
                headerType = cfg.headerType,
                seed = cfg.seed,
                enableFragment = options.enableFragment
            )
        )
        // XTLS-Vision is incompatible with Mux in Xray-core; guard automatically
        if (options.enableMux && !cfg.flow.contains("vision", ignoreCase = true)) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", 8)
            }
        }
    }

    private fun buildVmessOutbound(
        server: VpnServer,
        cfg: ProtocolConfig.Vmess,
        options: VpnBuildOptions
    ): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("protocol", "vmess")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    putJsonArray("users") {
                        add(buildJsonObject {
                            put("id", cfg.uuid)
                            put("alterId", cfg.alterId)
                            put("security", cfg.cipher.ifBlank { "auto" })
                            put("level", 8)
                        })
                    }
                })
            }
        }
        put(
            "streamSettings",
            buildStreamSettings(
                network = cfg.network,
                security = cfg.security.ifBlank { "none" },
                serverAddress = server.address,
                sni = cfg.sni,
                fingerprint = cfg.fingerprint,
                alpn = cfg.alpn,
                allowInsecure = cfg.allowInsecure || options.allowInsecureGlobal,
                path = cfg.path,
                host = cfg.host,
                serviceName = cfg.serviceName,
                authority = cfg.authority,
                headerType = cfg.headerType,
                seed = cfg.seed,
                enableFragment = options.enableFragment
            )
        )
        if (options.enableMux) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", 8)
            }
        }
    }

    private fun buildTrojanOutbound(
        server: VpnServer,
        cfg: ProtocolConfig.Trojan,
        options: VpnBuildOptions
    ): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("protocol", "trojan")
        putJsonObject("settings") {
            putJsonArray("servers") {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    put("password", cfg.password)
                    put("level", 8)
                })
            }
        }
        put(
            "streamSettings",
            buildStreamSettings(
                network = cfg.network,
                security = cfg.security.ifBlank { "tls" },
                serverAddress = server.address,
                sni = cfg.sni,
                fingerprint = cfg.fingerprint,
                alpn = cfg.alpn,
                allowInsecure = cfg.allowInsecure || options.allowInsecureGlobal,
                publicKey = cfg.publicKey,
                shortId = cfg.shortId,
                spiderX = cfg.spiderX,
                path = cfg.path,
                host = cfg.host,
                mode = cfg.mode,
                extraJson = cfg.extraJson,
                serviceName = cfg.serviceName,
                authority = cfg.authority,
                headerType = cfg.headerType,
                seed = cfg.seed,
                enableFragment = options.enableFragment
            )
        )
        if (options.enableMux) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", 8)
            }
        }
    }

    private fun buildShadowsocksOutbound(
        server: VpnServer,
        cfg: ProtocolConfig.Shadowsocks,
        options: VpnBuildOptions
    ): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("protocol", "shadowsocks")
        putJsonObject("settings") {
            putJsonArray("servers") {
                add(buildJsonObject {
                    put("address", server.address)
                    put("port", server.port)
                    put("method", cfg.method)
                    put("password", cfg.password)
                    put("level", 8)
                })
            }
        }
        putJsonObject("streamSettings") {
            put("network", "raw")
            if (options.enableFragment) {
                putJsonObject("sockopt") {
                    put("dialerProxy", "fragment")
                }
            }
        }
    }

    private fun buildStreamSettings(
        network: String,
        security: String,
        serverAddress: String,
        sni: String,
        fingerprint: String,
        alpn: List<String>,
        allowInsecure: Boolean,
        publicKey: String = "",
        shortId: String = "",
        spiderX: String = "",
        pqv: String = "",
        path: String = "",
        host: String = "",
        mode: String = "",
        extraJson: String = "",
        serviceName: String = "",
        authority: String = "",
        headerType: String = "",
        seed: String = "",
        enableFragment: Boolean = false
    ): JsonObject {
        val normalizedNet = when (network.lowercase()) {
            "splithttp", "xhttp" -> "xhttp"
            "tcp", "raw", "" -> "raw"
            "h2" -> "http"
            else -> network.lowercase()
        }
        val normalizedSec = security.lowercase().ifBlank { "none" }
        val effectiveSni = sni.ifBlank { host.ifBlank { serverAddress } }

        return buildJsonObject {
            put("network", normalizedNet)
            put("security", normalizedSec)

            if (enableFragment) {
                putJsonObject("sockopt") {
                    put("dialerProxy", "fragment")
                }
            }

            when (normalizedSec) {
                "tls" -> putJsonObject("tlsSettings") {
                    if (effectiveSni.isNotBlank()) put("serverName", effectiveSni)
                    put("allowInsecure", allowInsecure)
                    if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
                    if (alpn.isNotEmpty()) {
                        put("alpn", JsonArray(alpn.map { JsonPrimitive(it) }))
                    }
                }

                "reality" -> putJsonObject("realitySettings") {
                    if (effectiveSni.isNotBlank()) put("serverName", effectiveSni)
                    put("fingerprint", fingerprint.ifBlank { "chrome" })
                    put("publicKey", publicKey)
                    put("shortId", shortId)
                    put("spiderX", spiderX.ifBlank { "/" })
                    if (pqv.isNotBlank()) put("mldsa65Verify", pqv)
                }
            }

            when (normalizedNet) {
                "xhttp" -> putJsonObject("xhttpSettings") {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) put("host", host)
                    put("mode", mode.ifBlank { "auto" })
                    if (extraJson.isNotBlank()) {
                        runCatching { json.parseToJsonElement(extraJson) }.getOrNull()?.let { parsedExtra ->
                            put("extra", parsedExtra)
                        }
                    }
                }

                "ws" -> putJsonObject("wsSettings") {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) {
                        put("host", host)
                        putJsonObject("headers") { put("Host", host) }
                    }
                }

                "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) put("host", host)
                }

                "grpc" -> putJsonObject("grpcSettings") {
                    put("serviceName", serviceName.ifBlank { path.trimStart('/') })
                    if (authority.isNotBlank()) put("authority", authority)
                    put("multiMode", mode.equals("multi", ignoreCase = true))
                }

                "kcp" -> putJsonObject("kcpSettings") {
                    if (seed.isNotBlank()) put("seed", seed)
                    putJsonObject("header") {
                        put("type", headerType.ifBlank { "none" })
                    }
                }

                "raw" -> {
                    if (headerType.equals("http", ignoreCase = true)) {
                        putJsonObject("rawSettings") {
                            putJsonObject("header") {
                                put("type", "http")
                                putJsonObject("request") {
                                    putJsonArray("path") { add(JsonPrimitive(path.ifBlank { "/" })) }
                                    if (host.isNotBlank()) {
                                        putJsonObject("headers") {
                                            putJsonArray("Host") {
                                                host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                                    .forEach { add(JsonPrimitive(it)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MTU = 1500
        const val LOCAL_SOCKS_PORT = 10808

        private val LAN_CIDRS = listOf(
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "224.0.0.0/4",
            "::1/128",
            "fc00::/7",
            "fe80::/10"
        )

        private val IRAN_DIRECT_DOMAINS = listOf(
            "domain:ir",
            "domain:digikala.com",
            "domain:aparat.com",
            "domain:telewebion.com",
            "domain:varzesh3.com",
            "domain:filimo.com",
            "domain:namava.ir",
            "domain:blu.bank",
            "domain:bale.ai",
            "domain:eitaa.com",
            "domain:emofid.com",
            "domain:pasargadbank.com",
            "domain:samanbank.com",
            "domain:parsian-bank.com"
        )
    }
}
