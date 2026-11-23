package com.pasarguard.vpn.domain.repository

import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.AutoSelectMode
import com.pasarguard.vpn.domain.model.CalendarSystem
import com.pasarguard.vpn.domain.model.CheckoutSession
import com.pasarguard.vpn.domain.model.CorePreference
import com.pasarguard.vpn.domain.model.CustomRoutingRule
import com.pasarguard.vpn.domain.model.DnsPreset
import com.pasarguard.vpn.domain.model.NetworkAutoConnectRule
import com.pasarguard.vpn.domain.model.PingHistoryPoint
import com.pasarguard.vpn.domain.model.PingProgressState
import com.pasarguard.vpn.domain.model.PingQualityMetrics
import com.pasarguard.vpn.domain.model.PingTestMode
import com.pasarguard.vpn.domain.model.RenewalPlan
import com.pasarguard.vpn.domain.model.RoutingMode
import com.pasarguard.vpn.domain.model.ServerSortMode
import com.pasarguard.vpn.domain.model.SessionStatRecord
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.model.SubscriptionCredentials
import com.pasarguard.vpn.domain.model.SubscriptionFormatStrategy
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.SubscriptionProfile
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.model.VpnSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SubscriptionRepository {
    fun observeSubscription(): Flow<SubscriptionInfo?>
    fun observeUsageHistory(): Flow<List<UsagePoint>>
    fun observeProfiles(): Flow<List<SubscriptionProfile>>
    fun getSavedCredentials(): SubscriptionCredentials?
    suspend fun validateAndSaveSubscription(credentials: SubscriptionCredentials): Result<SubscriptionInfo>
    suspend fun addProfile(name: String, rawUrl: String, colorHex: Long): Result<SubscriptionProfile>
    suspend fun switchProfile(profileId: String): Result<SubscriptionInfo>
    suspend fun deleteProfile(profileId: String)
    suspend fun syncSubscription(): Result<SubscriptionInfo>
    suspend fun clearSession()
}

interface ServerRepository {
    val pingProgress: StateFlow<PingProgressState>
    fun observeServers(): Flow<List<VpnServer>>
    fun observePingHistory(serverId: String): Flow<List<PingHistoryPoint>>
    fun observeSessionStats(): Flow<List<SessionStatRecord>>
    suspend fun getCachedServers(): List<VpnServer>
    suspend fun getServerById(serverId: String): VpnServer?
    suspend fun importManualLinks(rawText: String): Int
    suspend fun deleteServer(serverId: String)
    suspend fun toggleFavorite(serverId: String)
    suspend fun updatePing(serverId: String, pingMs: Int)
    suspend fun recordSessionStat(record: SessionStatRecord)
    suspend fun clearSessionStats()
    suspend fun pingAllServers(): List<VpnServer>
    fun cancelBatchPing()
    suspend fun pingServerDetailed(server: VpnServer): PingQualityMetrics
    suspend fun pingServer(server: VpnServer): Int
}

/**
 * Billing, plans, discount validation, and card-to-card order repository contract.
 * Backed by [com.pasarguard.vpn.data.repository.FakePaymentRepository] until the real backend is deployed.
 */
interface PaymentRepository {
    suspend fun getAvailablePlans(): Result<List<RenewalPlan>>
    suspend fun createCheckoutSession(
        planId: String,
        subscriptionToken: String,
        callbackUrl: String
    ): Result<CheckoutSession>

    // Stage 2B — Full PlansConfig, Discount, Card-to-Card Orders & History
    suspend fun loadPlansCatalog(): Result<com.pasarguard.vpn.domain.model.PlansCatalogConfig>
    suspend fun validateDiscountCode(
        planId: String,
        code: String
    ): Result<com.pasarguard.vpn.domain.model.DiscountValidationResult>

    suspend fun createOrder(
        plan: com.pasarguard.vpn.domain.model.CatalogPlan,
        paymentMethod: com.pasarguard.vpn.domain.model.PaymentMethodType,
        discountCode: String?,
        idempotencyKey: String
    ): Result<com.pasarguard.vpn.domain.model.PaymentOrder>

    suspend fun submitCardReceipt(
        orderId: String,
        payerCardLast4: String,
        trackingNumber: String,
        receiptLocalPath: String?
    ): Result<com.pasarguard.vpn.domain.model.PaymentOrder>

    suspend fun refreshOrderStatus(orderId: String): Result<com.pasarguard.vpn.domain.model.PaymentOrder>
    fun observeOrders(): Flow<List<com.pasarguard.vpn.domain.model.PaymentOrder>>
    fun observeOrderById(orderId: String): Flow<com.pasarguard.vpn.domain.model.PaymentOrder?>
    suspend fun getOrderById(orderId: String): com.pasarguard.vpn.domain.model.PaymentOrder?
    suspend fun getActivePendingOrder(): com.pasarguard.vpn.domain.model.PaymentOrder?
    suspend fun simulateOrderStatus(
        orderId: String,
        targetStatus: com.pasarguard.vpn.domain.model.OrderStatus,
        reasonFa: String? = null,
        reasonEn: String? = null
    ): Result<com.pasarguard.vpn.domain.model.PaymentOrder>
}

/**
 * Customer account & HWID device actions contract (backend-ready).
 */
interface AccountActionsRepository {
    suspend fun listConnectedDevices(): Result<List<com.pasarguard.vpn.domain.model.ConnectedDevice>>
    suspend fun removeDevice(deviceId: String): Result<com.pasarguard.vpn.domain.model.AccountActionResult>
    suspend fun requestTrafficReset(): Result<com.pasarguard.vpn.domain.model.AccountActionResult>
    suspend fun requestNewSubscriptionLink(): Result<com.pasarguard.vpn.domain.model.AccountActionResult>
    suspend fun submitSupportTicket(
        request: com.pasarguard.vpn.domain.model.SupportTicketRequest
    ): Result<com.pasarguard.vpn.domain.model.AccountActionResult>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun getSnapshot(): AppSettings
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setCoachTipsDismissed(dismissed: Boolean)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setPersianDigitsEnabled(enabled: Boolean)
    suspend fun setCalendarSystem(system: CalendarSystem)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColorEnabled(enabled: Boolean)
    suspend fun setCorePreference(preference: CorePreference)
    suspend fun setSubscriptionFormatStrategy(strategy: SubscriptionFormatStrategy)
    suspend fun setAutoRefreshIntervalHours(hours: Int)
    suspend fun setDismissedAnnouncementHash(hash: String)

    // Ping & Auto-select
    suspend fun setPingTestMode(mode: PingTestMode)
    suspend fun setPingTestUrl(url: String)
    suspend fun setCustomPingUrls(urls: List<String>)
    suspend fun setPingSamplesPerServer(count: Int)
    suspend fun setPingTimeoutSec(seconds: Int)
    suspend fun setPingMaxConcurrency(concurrency: Int)
    suspend fun setAutoSelectMode(mode: AutoSelectMode)
    suspend fun setAutoTestOnAppOpen(enabled: Boolean)
    suspend fun setAutoTestBeforeAutoSelect(enabled: Boolean)
    suspend fun setHideDeadServers(hide: Boolean)
    suspend fun setServerSortMode(mode: ServerSortMode)

    // Diagnostics & Updates
    suspend fun setIpInfoEndpoint(url: String)
    suspend fun setSpeedTestUrl(url: String)
    suspend fun setUpdateCheckUrl(url: String)

    // Advanced Connection, Routing & DNS
    suspend fun setMtu(mtu: Int)
    suspend fun setEnableIpv6(enabled: Boolean)
    suspend fun setEnableFragment(enabled: Boolean)
    suspend fun setEnableMux(enabled: Boolean)
    suspend fun setEnableSniffing(enabled: Boolean)
    suspend fun setAllowInsecureGlobal(enabled: Boolean)
    suspend fun setRoutingMode(mode: RoutingMode)
    suspend fun setCustomRoutingRules(rules: List<CustomRoutingRule>)
    suspend fun setDnsPreset(preset: DnsPreset)
    suspend fun setCustomDnsServer(server: String)
    suspend fun setRemoteDnsThroughProxy(enabled: Boolean)

    // Automation & Split Tunneling
    suspend fun setAutoConnect(enabled: Boolean)
    suspend fun setNetworkAutoConnectRule(rule: NetworkAutoConnectRule)
    suspend fun setAutoReconnect(enabled: Boolean)
    suspend fun setAutoFailover(enabled: Boolean)
    suspend fun setKillSwitchEnabled(enabled: Boolean)
    suspend fun setBlockTrafficOnDrop(enabled: Boolean)
    suspend fun setSplitTunnelMode(mode: SplitTunnelMode)
    suspend fun setSplitTunnelPackages(packages: Set<String>)
    suspend fun setSelectedServerId(serverId: String?)

    // Privacy & Security
    suspend fun setAppLockEnabled(enabled: Boolean)
    suspend fun setAppLockPin(pin: String)
    suspend fun setHideInRecents(hide: Boolean)
    suspend fun setDisguiseNotification(disguise: Boolean)

    // Alerts
    suspend fun setExpiryWarningEnabled(enabled: Boolean)
    suspend fun setLowTrafficWarningEnabled(enabled: Boolean)

    // Backup / Restore JSON
    suspend fun exportSettingsJson(includeToken: Boolean, tokenOrNull: String?): String
    suspend fun importSettingsJson(rawJson: String): Result<String?>
}

interface VpnController {
    val sessionState: StateFlow<VpnSessionState>
    suspend fun connect(server: VpnServer, isAutoMode: Boolean)
    suspend fun disconnect()
    fun hasVpnPermission(): Boolean
}
