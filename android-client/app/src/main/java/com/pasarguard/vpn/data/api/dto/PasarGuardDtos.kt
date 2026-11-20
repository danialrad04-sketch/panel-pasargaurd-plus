package com.pasarguard.vpn.data.api.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * PasarGuard's `expire` field can arrive as either a Unix timestamp (Long) or an ISO-8601 string
 * depending on panel version / database migration. This serializer normalizes both to epoch seconds.
 */
object FlexibleEpochSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleEpochSeconds", PrimitiveKind.STRING)

    private val isoFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd HH:mm:ss"
    )

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString().toLongOrNull()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null

        primitive.longOrNull?.let { raw ->
            // Handle milliseconds vs seconds gracefully
            return if (raw > 10_000_000_000L) raw / 1000L else raw
        }

        val text = primitive.content.trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        text.toLongOrNull()?.let { return if (it > 10_000_000_000L) it / 1000L else it }

        for (pattern in isoFormats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(text)
                if (parsed != null) return parsed.time / 1000L
            }
        }
        return null
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

/**
 * Matches PasarGuard GET /sub/{token}/info payload (see server/store.ts -> buildUserResponse).
 */
@Serializable
data class PasarGuardSubInfoDto(
    val id: Long = 0L,
    val username: String = "",
    val status: String = "active",
    @SerialName("used_traffic") val usedTraffic: Long = 0L,
    @SerialName("lifetime_used_traffic") val lifetimeUsedTraffic: Long = 0L,
    @SerialName("data_limit") val dataLimit: Long? = null,
    @SerialName("data_limit_reset_strategy") val dataLimitResetStrategy: String = "no_reset",
    @Serializable(with = FlexibleEpochSerializer::class)
    val expire: Long? = null,
    @SerialName("on_hold_expire_duration") val onHoldExpireDuration: Long? = null,
    @SerialName("hwid_limit") val hwidLimit: Int? = null,
    @SerialName("sub_updated_at") val subUpdatedAt: String? = null,
    @SerialName("subscription_url") val subscriptionUrl: String = ""
)

/**
 * Matches PasarGuard GET /sub/{token}/raw payload.
 */
@Serializable
data class PasarGuardRawLinksDto(
    val links: List<String> = emptyList()
)

/**
 * Matches PasarGuard GET /sub/{token}/usage payload.
 */
@Serializable
data class PasarGuardSubUsageDto(
    @SerialName("used_traffic") val usedTraffic: Long = 0L,
    @SerialName("data_limit") val dataLimit: Long? = null,
    val stats: Map<String, List<UsageBucketDto>> = emptyMap()
)

@Serializable
data class UsageBucketDto(
    @SerialName("period_start") val periodStart: String = "",
    @SerialName("total_traffic") val totalTraffic: Long = 0L
)
