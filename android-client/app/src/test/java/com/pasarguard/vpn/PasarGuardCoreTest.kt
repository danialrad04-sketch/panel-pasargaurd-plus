package com.pasarguard.vpn

import com.pasarguard.vpn.data.api.dto.PasarGuardSubInfoDto
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.domain.model.CorePreference
import com.pasarguard.vpn.domain.model.ProtocolConfig
import com.pasarguard.vpn.domain.model.ProxyProtocol
import com.pasarguard.vpn.domain.model.VpnEngineType
import com.pasarguard.vpn.vpn.SingBoxConfigBuilder
import com.pasarguard.vpn.vpn.XrayConfigBuilder
import com.pasarguard.vpn.vpn.core.CoreSelectionResult
import com.pasarguard.vpn.vpn.core.CoreSelector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PasarGuardCoreTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val parser = ProxyLinkParser()
    private val xrayBuilder = XrayConfigBuilder()
    private val singBoxBuilder = SingBoxConfigBuilder()
    private val coreSelector = CoreSelector()

    @Test
    fun `flexible expire serializer parses both ISO-8601 and unix epoch`() {
        val isoJson = """{"id":1,"username":"alex","status":"active","used_traffic":1024,"data_limit":10737418240,"expire":"2026-12-31T23:59:59Z"}"""
        val epochJson = """{"id":2,"username":"sara","status":"on_hold","used_traffic":0,"data_limit":null,"expire":1798761599}"""

        val isoDto = json.decodeFromString(PasarGuardSubInfoDto.serializer(), isoJson)
        val epochDto = json.decodeFromString(PasarGuardSubInfoDto.serializer(), epochJson)

        assertNotNull(isoDto.expire)
        assertTrue(isoDto.expire!! > 1_700_000_000L)
        assertEquals(1798761599L, epochDto.expire)
    }

    @Test
    fun `parses and builds Xray JSON for VLESS Reality plus XTLS-Vision`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@185.199.108.153:443" +
            "?type=tcp&security=reality&flow=xtls-rprx-vision&sni=www.cloudflare.com" +
            "&fp=chrome&pbk=PubKeyBase64Value&sid=a1b2c3d4&spx=%2Fspider#DE-Reality-Vision"

        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        assertEquals(ProxyProtocol.VLESS, server!!.protocol)
        assertEquals("DE", server.countryCode)
        val cfg = server.config as ProtocolConfig.Vless
        assertEquals("xtls-rprx-vision", cfg.flow)
        assertEquals("reality", cfg.security)
        assertEquals("/spider", cfg.spiderX)

        val xrayJson = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        val inbounds = xrayJson["inbounds"]!!.jsonArray
        assertEquals("tun", inbounds[0].jsonObject["protocol"]!!.jsonPrimitive.content)

        val proxy = xrayJson["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("vless", proxy["protocol"]!!.jsonPrimitive.content)
        val user = proxy["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("xtls-rprx-vision", user["flow"]!!.jsonPrimitive.content)
        val reality = proxy["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
        assertEquals("PubKeyBase64Value", reality["publicKey"]!!.jsonPrimitive.content)
        assertEquals("a1b2c3d4", reality["shortId"]!!.jsonPrimitive.content)
        assertEquals("/spider", reality["spiderX"]!!.jsonPrimitive.content)

        // CoreSelector defaults to Xray
        val autoSelection = coreSelector.select(server, CorePreference.AUTO)
        assertEquals(CoreSelectionResult.Selected(VpnEngineType.XRAY), autoSelection)
    }

    @Test
    fun `parses and builds Xray JSON for VLESS XHTTP with mode and URL-encoded extra JSON`() {
        val extraEncoded = "%7B%22scMaxEachPostBytes%22%3A1000000%2C%22noGRPCHeader%22%3Atrue%7D"
        val uri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@edge.example.com:443" +
            "?type=xhttp&security=tls&sni=edge.example.com&host=cdn.example.com" +
            "&path=%2Fxhttp-api&mode=packet-up&extra=$extraEncoded#NL-XHTTP"

        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        val cfg = server!!.config as ProtocolConfig.Vless
        assertEquals("xhttp", cfg.network)
        assertEquals("packet-up", cfg.mode)
        assertEquals("/xhttp-api", cfg.path)
        assertTrue(cfg.extraJson.contains("scMaxEachPostBytes"))

        val xrayJson = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        val stream = xrayJson["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject
        assertEquals("xhttp", stream["network"]!!.jsonPrimitive.content)
        val xhttp = stream["xhttpSettings"]!!.jsonObject
        assertEquals("packet-up", xhttp["mode"]!!.jsonPrimitive.content)
        assertEquals("/xhttp-api", xhttp["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", xhttp["host"]!!.jsonPrimitive.content)
        assertEquals(1000000, xhttp["extra"]!!.jsonObject["scMaxEachPostBytes"]!!.jsonPrimitive.intOrNull)
        assertEquals(true, xhttp["extra"]!!.jsonObject["noGRPCHeader"]!!.jsonPrimitive.booleanOrNull)

        // Forcing sing-box on XHTTP must return a clear Incompatible error
        val forcedSingBox = coreSelector.select(server, CorePreference.SING_BOX)
        assertTrue(forcedSingBox is CoreSelectionResult.Incompatible)
        assertTrue((forcedSingBox as CoreSelectionResult.Incompatible).reason.contains("XHTTP", ignoreCase = true))
    }

    @Test
    fun `parses and builds Xray JSON for VLESS post-quantum encryption and pqv`() {
        val uri = "vless://12345678-1234-1234-1234-123456789abc@pq.example.com:443" +
            "?type=tcp&security=reality&encryption=mlkem768x25519plus.native.0rtt.SampleKey123" +
            "&pbk=TestPubKey&sid=99&pqv=MLDSA65VerifyKey#FI-PQ"

        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        val cfg = server!!.config as ProtocolConfig.Vless
        assertEquals("mlkem768x25519plus.native.0rtt.SampleKey123", cfg.encryption)
        assertEquals("MLDSA65VerifyKey", cfg.pqv)

        val xrayJson = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        val proxy = xrayJson["outbounds"]!!.jsonArray[0].jsonObject
        val user = proxy["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("mlkem768x25519plus.native.0rtt.SampleKey123", user["encryption"]!!.jsonPrimitive.content)
        val reality = proxy["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
        assertEquals("MLDSA65VerifyKey", reality["mldsa65Verify"]!!.jsonPrimitive.content)

        // sing-box cannot run VLESS encryption != none
        val forcedSingBox = coreSelector.select(server, CorePreference.SING_BOX)
        assertTrue(forcedSingBox is CoreSelectionResult.Incompatible)
    }

    @Test
    fun `parses and builds Xray JSON for VMess WS plus TLS`() {
        val vmessPayload = """
            {"v":"2","ps":"FR-VMess-WS","add":"ws.example.com","port":"443","id":"99999999-8888-7777-6666-555555555555","aid":"0","scy":"auto","net":"ws","type":"none","host":"ws.example.com","path":"/vmess-ws","tls":"tls","sni":"ws.example.com","fp":"firefox","alpn":"http/1.1"}
        """.trimIndent()
        val uri = "vmess://" + Base64.getEncoder().encodeToString(vmessPayload.toByteArray())

        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        assertEquals(ProxyProtocol.VMESS, server!!.protocol)

        val xrayJson = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        val proxy = xrayJson["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("vmess", proxy["protocol"]!!.jsonPrimitive.content)
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("ws", stream["network"]!!.jsonPrimitive.content)
        assertEquals("tls", stream["security"]!!.jsonPrimitive.content)
        assertEquals("/vmess-ws", stream["wsSettings"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses and builds Xray JSON for Trojan gRPC`() {
        val uri = "trojan://SuperSecretPass@tr.example.com:443?type=grpc&security=tls" +
            "&sni=tr.example.com&serviceName=grpcTunnel&authority=auth.example.com&mode=multi#TR-gRPC"

        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        assertEquals(ProxyProtocol.TROJAN, server!!.protocol)

        val xrayJson = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        val proxy = xrayJson["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("trojan", proxy["protocol"]!!.jsonPrimitive.content)
        val grpc = proxy["streamSettings"]!!.jsonObject["grpcSettings"]!!.jsonObject
        assertEquals("grpcTunnel", grpc["serviceName"]!!.jsonPrimitive.content)
        assertEquals("auth.example.com", grpc["authority"]!!.jsonPrimitive.content)
        assertEquals(true, grpc["multiMode"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `parses and builds Xray JSON for Shadowsocks SIP002`() {
        val userInfo = Base64.getEncoder().encodeToString("2022-blake3-aes-128-gcm:Base64KeyPass==".toByteArray())
        val uri = "ss://$userInfo@ss.example.com:8388#SG-SS2022"

        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        assertEquals(ProxyProtocol.SHADOWSOCKS, server!!.protocol)
        val cfg = server!!.config as ProtocolConfig.Shadowsocks
        assertEquals("2022-blake3-aes-128-gcm", cfg.method)
        assertEquals("Base64KeyPass==", cfg.password)

        val xrayJson = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        val proxy = xrayJson["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("shadowsocks", proxy["protocol"]!!.jsonPrimitive.content)
        val ssServer = proxy["settings"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        assertEquals("2022-blake3-aes-128-gcm", ssServer["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prefers panel Xray-JSON subscription verbatim and injects Android TUN inbound`() {
        val panelPayload = """
            [
              {
                "remarks": "DE-Fragment-Panel",
                "inbounds": [{"tag": "socks", "port": 10808, "protocol": "socks"}],
                "outbounds": [
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{"address": "1.2.3.4", "port": 443, "users": [{"id": "uuid-1", "encryption": "none"}]}]
                    },
                    "streamSettings": {
                      "network": "xhttp",
                      "security": "reality",
                      "sockopt": {"dialerProxy": "fragment"}
                    }
                  },
                  {
                    "tag": "fragment",
                    "protocol": "freedom",
                    "settings": {"fragment": {"packets": "tlshello", "length": "100-200", "interval": "10-20"}}
                  }
                ]
              }
            ]
        """.trimIndent()

        val servers = parser.parseXrayJsonSubscription(panelPayload)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals("DE-Fragment-Panel", server.name)
        assertNotNull(server.rawXrayConfigJson)

        val built = json.parseToJsonElement(xrayBuilder.buildConfigJson(server)).jsonObject
        // Android TUN inbound injected
        assertEquals("tun", built["inbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        // Panel's fragment outbound preserved verbatim
        assertEquals(2, built["outbounds"]!!.jsonArray.size)
        assertEquals("fragment", built["outbounds"]!!.jsonArray[1].jsonObject["tag"]!!.jsonPrimitive.content)
        // Stats & policy enabled for live speed graph
        assertNotNull(built["stats"])
        assertEquals(true, built["policy"]!!.jsonObject["system"]!!.jsonObject["statsOutboundDownlink"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `coreSelector routes Hysteria2 to sing-box and builds valid sing-box JSON`() {
        val uri = "hysteria2://MyHy2Pass@hy2.example.com:443?sni=hy2.example.com&obfs=salamander&obfs-password=obfsKey#US-Hy2"
        val server = parser.parseSingleUri(uri)
        assertNotNull(server)
        assertEquals(ProxyProtocol.HYSTERIA2, server!!.protocol)

        // Auto routes to sing-box; forced Xray returns clear error
        assertEquals(CoreSelectionResult.Selected(VpnEngineType.SING_BOX), coreSelector.select(server, CorePreference.AUTO))
        assertTrue(coreSelector.select(server, CorePreference.XRAY) is CoreSelectionResult.Incompatible)

        val sbJson = json.parseToJsonElement(singBoxBuilder.buildConfigJson(server, enableClashApi = true)).jsonObject
        val proxy = sbJson["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("hysteria2", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("MyHy2Pass", proxy["password"]!!.jsonPrimitive.content)
        assertEquals("salamander", proxy["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pingScoring computes avg, min, max, jitter, packet loss, and stability tiers`() {
        val excellent = com.pasarguard.vpn.domain.model.PingScoring.evaluate(listOf(80, 86, 84))
        assertEquals(83, excellent.avgMs)
        assertEquals(80, excellent.minMs)
        assertEquals(86, excellent.maxMs)
        assertEquals(0, excellent.packetLossPct)
        assertEquals(com.pasarguard.vpn.domain.model.StabilityTier.EXCELLENT, excellent.tier)
        assertTrue(excellent.stabilityScore >= 80)

        val partialLoss = com.pasarguard.vpn.domain.model.PingScoring.evaluate(listOf(120, -1, 300))
        assertEquals(210, partialLoss.avgMs)
        assertEquals(33, partialLoss.packetLossPct)
        assertTrue(partialLoss.jitterMs > 50)
        assertTrue(partialLoss.stabilityScore < excellent.stabilityScore)

        val dead = com.pasarguard.vpn.domain.model.PingScoring.evaluate(listOf(-1, -1))
        assertEquals(-1, dead.avgMs)
        assertEquals(100, dead.packetLossPct)
        assertEquals(0, dead.stabilityScore)
        assertEquals(com.pasarguard.vpn.domain.model.StabilityTier.DEAD, dead.tier)
    }

    @Test
    fun `jalaliCalendar converts Gregorian dates accurately and formats Persian digits with separators`() {
        // 2024-03-20 is Nowruz 1403/01/01
        val nowruz = com.pasarguard.vpn.util.JalaliCalendar.gregorianToJalali(2024, 3, 20)
        assertEquals(1403, nowruz.year)
        assertEquals(1, nowruz.month)
        assertEquals(1, nowruz.day)

        // 2026-03-21 is 1405/01/01
        val nowruz1405 = com.pasarguard.vpn.util.JalaliCalendar.gregorianToJalali(2026, 3, 21)
        assertEquals(1405, nowruz1405.year)
        assertEquals(1, nowruz1405.month)
        assertEquals(1, nowruz1405.day)

        assertEquals("۱۵۰٬۰۰۰", com.pasarguard.vpn.util.JalaliCalendar.formatThousands(150_000L, persianDigits = true))
        assertEquals("150,000", com.pasarguard.vpn.util.JalaliCalendar.formatThousands(150_000L, persianDigits = false))
    }

    @Test
    fun `subscriptionHeaderParser decodes plain and base64 headers cleanly`() {
        val encodedTitle = "base64:" + Base64.getEncoder().encodeToString("پاسارگارد پرو".toByteArray(Charsets.UTF_8))
        val encodedAnnounce = "base64:" + Base64.getEncoder().encodeToString("New Germany nodes online!".toByteArray(Charsets.UTF_8))

        val meta = com.pasarguard.vpn.data.parser.SubscriptionHeaderParser(parser).parseMetadata(
            mapOf(
                "Profile-Title" to encodedTitle,
                "Support-Url" to "https://t.me/pasarguard_support",
                "Profile-Web-Page-Url" to "https://panel.example.com/sub/info",
                "Announce" to encodedAnnounce,
                "Profile-Update-Interval" to "12"
            )
        )
        assertEquals("پاسارگارد پرو", meta.profileTitle)
        assertEquals("https://t.me/pasarguard_support", meta.supportUrl)
        assertEquals("https://panel.example.com/sub/info", meta.profileWebPageUrl)
        assertEquals("New Germany nodes online!", meta.announce)
        assertEquals(12, meta.updateIntervalHours)
    }

    @Test
    fun `sanitizedLogBuffer redacts UUIDs, proxy links, tokens, and secrets`() {
        val dirty = "Failed connecting vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?pbk=MySecretKey with https://panel.example.com/sub/SecretTok99"
        val clean = com.pasarguard.vpn.util.SanitizedLogBuffer.sanitize(dirty)
        assertTrue(!clean.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(!clean.contains("MySecretKey"))
        assertTrue(!clean.contains("SecretTok99"))
        assertTrue(clean.contains("REDACTED"))
    }

    @Test
    fun `xrayConfigBuilder applies Bypass Iran routing, custom DNS, Fragment, and guards Mux on Vision`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@185.199.108.153:443" +
            "?type=tcp&security=tls&sni=example.com#DE-TLS"
        val server = parser.parseSingleUri(uri)!!

        val options = com.pasarguard.vpn.vpn.core.VpnBuildOptions(
            mtu = 1400,
            enableIpv6 = false,
            routingMode = com.pasarguard.vpn.domain.model.RoutingMode.BYPASS_IRAN,
            dnsPreset = com.pasarguard.vpn.domain.model.DnsPreset.GOOGLE_DOH,
            remoteDnsThroughProxy = true,
            enableFragment = true,
            enableMux = true,
            enableSniffing = false,
            geoFilesAvailable = true
        )

        val built = json.parseToJsonElement(xrayBuilder.buildConfigJson(server, options)).jsonObject
        val outbounds = built["outbounds"]!!.jsonArray
        val proxy = outbounds[0].jsonObject
        assertEquals("fragment", proxy["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content)
        assertEquals(true, proxy["mux"]!!.jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(outbounds.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "fragment" })

        val dnsServers = built["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(dnsServers.first().contains("8.8.8.8"))

        val rules = built["routing"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(rules.toString().contains("geosite:category-ir"))
        assertTrue(rules.toString().contains("geoip:ir"))
    }

    // ==================== Stage 2B Unit Tests ====================

    @Test
    fun `usageForecastCalculator predicts remaining days, early runout warning, and smart plan recommendation`() {
        val gb = 1024L * 1024L * 1024L
        val nowSec = 1_760_000_000L
        val sub = com.pasarguard.vpn.domain.model.SubscriptionInfo(
            username = "ali_pro",
            status = com.pasarguard.vpn.domain.model.SubscriptionStatus.ACTIVE,
            usedTrafficBytes = 40L * gb,
            lifetimeUsedTrafficBytes = 40L * gb,
            dataLimitBytes = 50L * gb, // 10 GB left
            resetStrategy = "no_reset",
            expireEpochSeconds = nowSec + (20L * 86_400L), // 20 days left
            onHoldDurationSeconds = null,
            hwidLimit = 3,
            subscriptionUrl = "https://panel.example.com/sub/tok",
            subUpdatedAt = null,
            lastSyncedEpochMs = nowSec * 1000L
        )
        // Average 2 GB/day -> 10 GB lasts ~5 days (< 20 days until expiry => warning!)
        val usage = listOf(
            com.pasarguard.vpn.domain.model.UsagePoint("D1", 1L * gb),
            com.pasarguard.vpn.domain.model.UsagePoint("D2", 3L * gb),
            com.pasarguard.vpn.domain.model.UsagePoint("D3", 2L * gb)
        )
        val plans = listOf(
            com.pasarguard.vpn.domain.model.CatalogPlan(
                id = "small_30g",
                category = com.pasarguard.vpn.domain.model.PlanCategory.RENEW,
                titleFa = "۳۰ گیگ",
                titleEn = "30 GB",
                durationDays = 30,
                dataLimitGb = 30,
                priceToman = 120_000L
            ),
            com.pasarguard.vpn.domain.model.CatalogPlan(
                id = "medium_150g",
                category = com.pasarguard.vpn.domain.model.PlanCategory.RENEW,
                titleFa = "۱۵۰ گیگ",
                titleEn = "150 GB",
                durationDays = 90,
                dataLimitGb = 150,
                priceToman = 420_000L
            ),
            com.pasarguard.vpn.domain.model.CatalogPlan(
                id = "pro_250g",
                category = com.pasarguard.vpn.domain.model.PlanCategory.UPGRADE,
                titleFa = "۲۵۰ گیگ",
                titleEn = "250 GB",
                durationDays = 90,
                dataLimitGb = 250,
                priceToman = 590_000L
            )
        )

        val forecast = com.pasarguard.vpn.domain.model.UsageForecastCalculator.calculate(
            subscription = sub,
            usagePoints = usage,
            catalogPlans = plans,
            nowEpochSeconds = nowSec
        )

        assertEquals(2L * gb, forecast.averageDailyBytes)
        assertEquals("D2", forecast.peakDayLabel)
        assertEquals(3L * gb, forecast.peakDayBytes)
        assertEquals(5, forecast.estimatedDaysRemaining)
        assertTrue(forecast.willRunOutBeforeExpiry)
        // Monthly projected usage = 60 GB/month -> 250GB/3m (~83GB/m) fits best
        assertEquals("pro_250g", forecast.recommendedPlanId)
    }

    @Test
    fun `orderStateMachine validates transitions, window expiration, and polling backoff`() {
        val sm = com.pasarguard.vpn.domain.model.OrderStateMachine
        assertTrue(sm.canTransition(com.pasarguard.vpn.domain.model.OrderStatus.AWAITING_RECEIPT, com.pasarguard.vpn.domain.model.OrderStatus.PENDING_REVIEW))
        assertTrue(sm.canTransition(com.pasarguard.vpn.domain.model.OrderStatus.PENDING_REVIEW, com.pasarguard.vpn.domain.model.OrderStatus.APPROVED))
        assertTrue(sm.canTransition(com.pasarguard.vpn.domain.model.OrderStatus.REJECTED, com.pasarguard.vpn.domain.model.OrderStatus.PENDING_REVIEW))
        assertTrue(!sm.canTransition(com.pasarguard.vpn.domain.model.OrderStatus.APPROVED, com.pasarguard.vpn.domain.model.OrderStatus.REJECTED))
        assertTrue(!sm.canTransition(com.pasarguard.vpn.domain.model.OrderStatus.EXPIRED, com.pasarguard.vpn.domain.model.OrderStatus.PENDING_REVIEW))

        val order = com.pasarguard.vpn.domain.model.PaymentOrder(
            id = "PG-TEST01",
            idempotencyKey = "idem-1",
            planId = "renew_3m_150g",
            planTitleFa = "سه‌ماهه",
            planTitleEn = "3 Months",
            planCategory = com.pasarguard.vpn.domain.model.PlanCategory.RENEW,
            paymentMethod = com.pasarguard.vpn.domain.model.PaymentMethodType.CARD_TO_CARD,
            status = com.pasarguard.vpn.domain.model.OrderStatus.AWAITING_RECEIPT,
            basePriceToman = 420_000L,
            finalPriceToman = 357_000L,
            destinationCardNumber = "6037997512348901",
            destinationCardHolderFa = "پاسارگارد",
            destinationCardHolderEn = "PasarGuard",
            destinationBankFa = "ملی",
            destinationBankEn = "Melli",
            createdAtMs = 1_000L,
            expiresAtMs = 1_801_000L
        )

        assertEquals(
            com.pasarguard.vpn.domain.model.OrderStatus.AWAITING_RECEIPT,
            sm.evaluateEffectiveStatus(order, nowMs = 500_000L)
        )
        assertEquals(
            com.pasarguard.vpn.domain.model.OrderStatus.EXPIRED,
            sm.evaluateEffectiveStatus(order, nowMs = 1_900_000L)
        )
        assertTrue(sm.nextPollDelayMs(0) < sm.nextPollDelayMs(3))
        assertTrue(sm.nextPollDelayMs(99) == 20_000L)
    }

    @Test
    fun `catalogPlan computes discount percent, price per GB, and price per day accurately`() {
        val plan = com.pasarguard.vpn.domain.model.CatalogPlan(
            id = "renew_3m_150g",
            category = com.pasarguard.vpn.domain.model.PlanCategory.RENEW,
            titleFa = "سه‌ماهه • ۱۵۰ گیگ",
            titleEn = "3 Months • 150 GB",
            durationDays = 90,
            dataLimitGb = 150,
            priceToman = 450_000L,
            oldPriceToman = 500_000L,
            maxDevices = 3
        )
        assertEquals(10, plan.discountPercent)
        assertEquals(3_000L, plan.pricePerGbToman)
        assertEquals(5_000L, plan.pricePerDayToman)
        assertEquals("6037  9975  1234  8901", com.pasarguard.vpn.domain.model.DestinationCardInfo("6037997512348901", "", "", "", "").formattedCardNumber())
    }
}
