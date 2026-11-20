package com.pasarguard.vpn.domain.repository

import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.CheckoutSession
import com.pasarguard.vpn.domain.model.RenewalPlan
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.model.SubscriptionCredentials
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.model.VpnSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SubscriptionRepository {
    fun observeSubscription(): Flow<SubscriptionInfo?>
    fun observeUsageHistory(): Flow<List<UsagePoint>>
    fun getSavedCredentials(): SubscriptionCredentials?
    suspend fun validateAndSaveSubscription(credentials: SubscriptionCredentials): Result<SubscriptionInfo>
    suspend fun syncSubscription(): Result<SubscriptionInfo>
    suspend fun clearSession()
}

interface ServerRepository {
    fun observeServers(): Flow<List<VpnServer>>
    suspend fun getCachedServers(): List<VpnServer>
    suspend fun toggleFavorite(serverId: String)
    suspend fun updatePing(serverId: String, pingMs: Int)
    suspend fun pingAllServers(): List<VpnServer>
    suspend fun pingServer(server: VpnServer): Int
}

/**
 * Clean payment gateway contract.
 * Replace [com.pasarguard.vpn.data.repository.PlaceholderPaymentRepository] with your real
 * gateway (Zarinpal, Cryptomus, Telegram Bot API, Stripe, or PasarGuard storefront BFF)
 * without changing any UI or domain code.
 */
interface PaymentRepository {
    suspend fun getAvailablePlans(): Result<List<RenewalPlan>>
    suspend fun createCheckoutSession(
        planId: String,
        subscriptionToken: String,
        callbackUrl: String
    ): Result<CheckoutSession>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun getSnapshot(): AppSettings
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAutoConnect(enabled: Boolean)
    suspend fun setKillSwitchEnabled(enabled: Boolean)
    suspend fun setSplitTunnelMode(mode: SplitTunnelMode)
    suspend fun setSplitTunnelPackages(packages: Set<String>)
    suspend fun setSelectedServerId(serverId: String?)
    suspend fun setExpiryWarningEnabled(enabled: Boolean)
    suspend fun setLowTrafficWarningEnabled(enabled: Boolean)
}

interface VpnController {
    val sessionState: StateFlow<VpnSessionState>
    suspend fun connect(server: VpnServer, isAutoMode: Boolean)
    suspend fun disconnect()
    fun hasVpnPermission(): Boolean
}
