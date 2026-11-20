package com.pasarguard.vpn.domain.usecase

import android.net.Uri
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.SubscriptionCredentials
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.repository.VpnController
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Normalizes either a full PasarGuard subscription URL (e.g. https://panel.example.com/sub/xyz123)
 * or a bare subscription token (xyz123) into structured [SubscriptionCredentials].
 */
class ParseSubscriptionInputUseCase @Inject constructor() {
    operator fun invoke(rawInput: String): Result<SubscriptionCredentials> {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("EMPTY_INPUT"))
        }

        // Handle deep-link format: pasarguardvpn://import?url=https://.../sub/token
        if (cleaned.startsWith("${BrandConfig.paymentCallbackScheme}://", ignoreCase = true)) {
            val uri = Uri.parse(cleaned)
            val embedded = uri.getQueryParameter("url") ?: uri.getQueryParameter("token")
            if (!embedded.isNullOrBlank()) {
                return invoke(embedded)
            }
        }

        return if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            val uri = Uri.parse(cleaned)
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host
            if (host.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("INVALID_URL"))
            }
            val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
            val baseUrl = "$scheme://$host$portSuffix"

            val segments = uri.pathSegments.filter { it.isNotBlank() }
            val subIndex = segments.indexOfFirst { it.equals("sub", ignoreCase = true) }
            val token = when {
                subIndex != -1 && subIndex + 1 < segments.size -> segments[subIndex + 1]
                segments.isNotEmpty() -> segments.last()
                else -> ""
            }
            if (token.isBlank()) {
                Result.failure(IllegalArgumentException("MISSING_TOKEN"))
            } else {
                Result.success(SubscriptionCredentials(baseUrl = baseUrl, token = token))
            }
        } else {
            // Bare token uses the configured BrandConfig.defaultBaseUrl
            val sanitizedToken = cleaned.substringAfterLast("/").trim()
            if (sanitizedToken.length < 3 || sanitizedToken.contains(" ")) {
                Result.failure(IllegalArgumentException("INVALID_TOKEN"))
            } else {
                Result.success(
                    SubscriptionCredentials(
                        baseUrl = BrandConfig.defaultBaseUrl,
                        token = sanitizedToken
                    )
                )
            }
        }
    }
}

class ValidateAndLoginUseCase @Inject constructor(
    private val parseInput: ParseSubscriptionInputUseCase,
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(rawInput: String): Result<SubscriptionInfo> {
        val credentials = parseInput(rawInput).getOrElse { return Result.failure(it) }
        return subscriptionRepository.validateAndSaveSubscription(credentials)
    }
}

class SyncSubscriptionUseCase @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(): Result<SubscriptionInfo> =
        subscriptionRepository.syncSubscription()
}

class SelectBestServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(preferredServerId: String?): Pair<VpnServer?, Boolean> {
        val cached = serverRepository.getCachedServers()
        if (cached.isEmpty()) return null to true

        if (preferredServerId != null) {
            val exact = cached.firstOrNull { it.id == preferredServerId }
            if (exact != null) return exact to false
        }

        // Auto (Best Ping): test live latency and pick lowest positive ping
        val tested = serverRepository.pingAllServers()
        val best = tested
            .filter { (it.pingMs ?: -1) > 0 }
            .minByOrNull { it.pingMs ?: Int.MAX_VALUE }
            ?: tested.firstOrNull()

        return best to true
    }
}

/**
 * Polls PasarGuard's /sub/{token}/info after a Chrome Custom Tabs payment redirect
 * until expiry or data limit increases relative to the pre-checkout snapshot.
 */
class PollRenewalStatusUseCase @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        previousExpire: Long?,
        previousDataLimit: Long?,
        maxAttempts: Int = 12,
        intervalMs: Long = 3_500L
    ): Result<Boolean> {
        repeat(maxAttempts) { attempt ->
            val result = subscriptionRepository.syncSubscription()
            val updated = result.getOrNull()
            if (updated != null) {
                val expireIncreased = (updated.expireEpochSeconds ?: 0L) > (previousExpire ?: 0L)
                val limitIncreased = (updated.dataLimitBytes ?: Long.MAX_VALUE) > (previousDataLimit ?: 0L)
                val reactivated = updated.status == com.pasarguard.vpn.domain.model.SubscriptionStatus.ACTIVE &&
                    (previousExpire != updated.expireEpochSeconds || previousDataLimit != updated.dataLimitBytes)
                if (expireIncreased || limitIncreased || reactivated) {
                    return Result.success(true)
                }
            }
            if (attempt < maxAttempts - 1) {
                delay(intervalMs)
            }
        }
        return Result.success(false)
    }
}

class LogoutUseCase @Inject constructor(
    private val vpnController: VpnController,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke() {
        vpnController.disconnect()
        subscriptionRepository.clearSession()
        settingsRepository.setSelectedServerId(null)
    }
}
