package com.pasarguard.vpn.vpn

import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.ProtocolConfig
import com.pasarguard.vpn.domain.model.VpnServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiles any parsed PasarGuard [VpnServer] (VLESS Reality/Vision/WS/gRPC, VMess, Trojan,
 * Shadowsocks) into a complete `sing-box` 1.10+ JSON configuration with native TUN inbound,
 * private-LAN bypass routing, and local Clash API telemetry on 127.0.0.1.
 */
@Singleton
class SingBoxConfigBuilder @Inject constructor() {

    fun buildConfigJson(
        server: VpnServer,
        enableClashApi: Boolean = true,
        mtu: Int = 1500,
        enableIpv6: Boolean = false
    ): String {
        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "dns-remote")
                        put("address", "https://1.1.1.1/dns-query")
                        put("detour", "proxy")
                    })
                    add(buildJsonObject {
                        put("tag", "dns-direct")
                        put("address", "1.1.1.1")
                        put("detour", "direct")
                    })
                }
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("outbound", "any")
                        put("server", "dns-direct")
                    })
                }
                put("final", "dns-remote")
                put("strategy", if (enableIpv6) "prefer_ipv4" else "ipv4_only")
            }

            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", "tun0")
                    putJsonArray("address") {
                        add(JsonPrimitive("172.19.0.1/30"))
                        if (enableIpv6) {
                            add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                        }
                    }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "gvisor")
                    put("sniff", true)
                    put("sniff_override_destination", false)
                })
            }

            putJsonArray("outbounds") {
                add(buildProxyOutbound(server))
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
                add(buildJsonObject {
                    put("type", "block")
                    put("tag", "block")
                })
                add(buildJsonObject {
                    put("type", "dns")
                    put("tag", "dns-out")
                })
            }

            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("outbound", "dns-out")
                    })
                    add(buildJsonObject {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    })
                }
                put("auto_detect_interface", true)
                put("final", "proxy")
            }

            if (enableClashApi) {
                putJsonObject("experimental") {
                    putJsonObject("clash_api") {
                        put("external_controller", "127.0.0.1:${BrandConfig.clashApiPort}")
                    }
                }
            }
        }
        return root.toString()
    }

    private fun buildProxyOutbound(server: VpnServer): JsonObject = when (val cfg = server.config) {
        is ProtocolConfig.Vless -> buildJsonObject {
            put("type", "vless")
            put("tag", "proxy")
            put("server", server.address)
            put("server_port", server.port)
            put("uuid", cfg.uuid)
            if (cfg.flow.isNotBlank()) put("flow", cfg.flow)
            put("packet_encoding", cfg.packetEncoding.ifBlank { "xudp" })
            buildTlsObject(
                security = cfg.security,
                sni = cfg.sni.ifBlank { server.address },
                fingerprint = cfg.fingerprint,
                alpn = cfg.alpn,
                allowInsecure = cfg.allowInsecure,
                realityPublicKey = cfg.publicKey,
                realityShortId = cfg.shortId
            )?.let { put("tls", it) }
            buildTransportObject(
                network = cfg.network,
                path = cfg.path,
                host = cfg.host,
                serviceName = cfg.serviceName
            )?.let { put("transport", it) }
        }

        is ProtocolConfig.Vmess -> buildJsonObject {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", server.address)
            put("server_port", server.port)
            put("uuid", cfg.uuid)
            put("security", cfg.cipher.ifBlank { "auto" })
            put("alter_id", cfg.alterId)
            if (cfg.security.equals("tls", ignoreCase = true)) {
                buildTlsObject(
                    security = "tls",
                    sni = cfg.sni.ifBlank { server.address },
                    fingerprint = cfg.fingerprint,
                    alpn = cfg.alpn,
                    allowInsecure = cfg.allowInsecure
                )?.let { put("tls", it) }
            }
            buildTransportObject(
                network = cfg.network,
                path = cfg.path,
                host = cfg.host,
                serviceName = cfg.serviceName
            )?.let { put("transport", it) }
        }

        is ProtocolConfig.Trojan -> buildJsonObject {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", server.address)
            put("server_port", server.port)
            put("password", cfg.password)
            buildTlsObject(
                security = cfg.security.ifBlank { "tls" },
                sni = cfg.sni.ifBlank { server.address },
                fingerprint = cfg.fingerprint,
                alpn = cfg.alpn,
                allowInsecure = cfg.allowInsecure,
                realityPublicKey = cfg.publicKey,
                realityShortId = cfg.shortId
            )?.let { put("tls", it) }
            buildTransportObject(
                network = cfg.network,
                path = cfg.path,
                host = cfg.host,
                serviceName = cfg.serviceName
            )?.let { put("transport", it) }
        }

        is ProtocolConfig.Shadowsocks -> buildJsonObject {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", server.address)
            put("server_port", server.port)
            put("method", cfg.method)
            put("password", cfg.password)
            if (cfg.plugin.isNotBlank()) {
                put("plugin", cfg.plugin)
                if (cfg.pluginOpts.isNotBlank()) put("plugin_opts", cfg.pluginOpts)
            }
        }

        is ProtocolConfig.SingBoxOnly -> buildSingBoxOnlyOutbound(server, cfg)
    }

    private fun buildSingBoxOnlyOutbound(
        server: VpnServer,
        cfg: ProtocolConfig.SingBoxOnly
    ): JsonObject {
        val p = cfg.params
        val sni = p["sni"].orEmpty().ifBlank { server.address }
        val insecure = p["insecure"] in setOf("1", "true") || p["allowInsecure"] in setOf("1", "true")
        return when (cfg.protocol) {
            com.pasarguard.vpn.domain.model.ProxyProtocol.HYSTERIA2 -> buildJsonObject {
                put("type", "hysteria2")
                put("tag", "proxy")
                put("server", server.address)
                put("server_port", server.port)
                put("password", p["userInfo"].orEmpty())
                val obfs = p["obfs"].orEmpty()
                if (obfs.isNotBlank()) {
                    putJsonObject("obfs") {
                        put("type", obfs)
                        put("password", p["obfs-password"].orEmpty())
                    }
                }
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", sni)
                    if (insecure) put("insecure", true)
                }
            }

            com.pasarguard.vpn.domain.model.ProxyProtocol.TUIC -> {
                val creds = p["userInfo"].orEmpty()
                val uuid = creds.substringBefore(":")
                val password = creds.substringAfter(":", "")
                buildJsonObject {
                    put("type", "tuic")
                    put("tag", "proxy")
                    put("server", server.address)
                    put("server_port", server.port)
                    put("uuid", uuid)
                    put("password", password)
                    put("congestion_control", p["congestion_control"].orEmpty().ifBlank { "bbr" })
                    putJsonObject("tls") {
                        put("enabled", true)
                        put("server_name", sni)
                        if (insecure) put("insecure", true)
                    }
                }
            }

            com.pasarguard.vpn.domain.model.ProxyProtocol.WIREGUARD -> buildJsonObject {
                put("type", "wireguard")
                put("tag", "proxy")
                put("server", server.address)
                put("server_port", server.port)
                put("private_key", p["userInfo"].orEmpty())
                put("peer_public_key", p["publickey"].orEmpty().ifBlank { p["publicKey"].orEmpty() })
                val addr = p["address"].orEmpty().ifBlank { "172.16.0.2/32" }
                putJsonArray("local_address") {
                    addr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        add(JsonPrimitive(it))
                    }
                }
                put("mtu", p["mtu"]?.toIntOrNull() ?: 1280)
            }

            else -> throw IllegalArgumentException("Unsupported sing-box protocol: ${cfg.protocol.label}")
        }
    }

    private fun buildTlsObject(
        security: String,
        sni: String,
        fingerprint: String,
        alpn: List<String>,
        allowInsecure: Boolean,
        realityPublicKey: String = "",
        realityShortId: String = ""
    ): JsonObject? {
        if (security.isBlank() || security.equals("none", ignoreCase = true)) return null
        return buildJsonObject {
            put("enabled", true)
            if (sni.isNotBlank()) put("server_name", sni)
            if (allowInsecure) put("insecure", true)
            if (alpn.isNotEmpty()) {
                put("alpn", JsonArray(alpn.map { JsonPrimitive(it) }))
            }
            if (fingerprint.isNotBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                }
            }
            if (security.equals("reality", ignoreCase = true) && realityPublicKey.isNotBlank()) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", realityPublicKey)
                    put("short_id", realityShortId)
                }
            }
        }
    }

    private fun buildTransportObject(
        network: String,
        path: String,
        host: String,
        serviceName: String
    ): JsonObject? = when (network.lowercase()) {
        "ws" -> buildJsonObject {
            put("type", "ws")
            put("path", path.ifBlank { "/" })
            if (host.isNotBlank()) {
                putJsonObject("headers") {
                    put("Host", host)
                }
            }
        }

        "grpc" -> buildJsonObject {
            put("type", "grpc")
            put("service_name", serviceName.ifBlank { path.trimStart('/') })
        }

        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            put("path", path.ifBlank { "/" })
            if (host.isNotBlank()) put("host", host)
        }

        "h2", "http" -> buildJsonObject {
            put("type", "http")
            if (host.isNotBlank()) {
                putJsonArray("host") { add(JsonPrimitive(host)) }
            }
            put("path", path.ifBlank { "/" })
        }

        else -> null
    }
}
