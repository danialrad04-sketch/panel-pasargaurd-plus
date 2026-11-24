package com.pasarguard.vpn.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.AutoSelectMode
import com.pasarguard.vpn.domain.model.CalendarSystem
import com.pasarguard.vpn.domain.model.CorePreference
import com.pasarguard.vpn.domain.model.CustomRoutingRule
import com.pasarguard.vpn.domain.model.DnsPreset
import com.pasarguard.vpn.domain.model.NetworkAutoConnectRule
import com.pasarguard.vpn.domain.model.PingScoring
import com.pasarguard.vpn.domain.model.PingTestMode
import com.pasarguard.vpn.domain.model.RoutingMode
import com.pasarguard.vpn.domain.model.ServerSortMode
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.model.SubscriptionFormatStrategy
import com.pasarguard.vpn.domain.model.ThemePalette
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pasarguard_settings"
)

@Serializable
private data class SettingsBackupPayload(
    val schemaVersion: Int = 1,
    val language: String = "fa",
    val persianDigitsEnabled: Boolean = true,
    val calendarSystem: String = CalendarSystem.JALALI.name,
    val themeMode: String = ThemeMode.DARK.name,
    val themePalette: String = ThemePalette.OCEAN.name,
    val dynamicColorEnabled: Boolean = false,
    val corePreference: String = CorePreference.AUTO.name,
    val subscriptionFormatStrategy: String = SubscriptionFormatStrategy.PREFER_XRAY_JSON.name,
    val autoRefreshIntervalHours: Int = 6,
    val pingTestMode: String = PingTestMode.THROUGH_CORE.name,
    val pingTestUrl: String = PingScoring.DEFAULT_TEST_URLS.first(),
    val customPingUrls: List<String> = emptyList(),
    val pingSamplesPerServer: Int = 2,
    val pingTimeoutSec: Int = 5,
    val pingMaxConcurrency: Int = 5,
    val autoSelectMode: String = AutoSelectMode.LOWEST_PING.name,
    val autoTestOnAppOpen: Boolean = false,
    val autoTestBeforeAutoSelect: Boolean = true,
    val hideDeadServers: Boolean = false,
    val serverSortMode: String = ServerSortMode.PING.name,
    val mtu: Int = 1500,
    val enableIpv6: Boolean = false,
    val enableFragment: Boolean = false,
    val enableMux: Boolean = false,
    val enableSniffing: Boolean = true,
    val allowInsecureGlobal: Boolean = false,
    val routingMode: String = RoutingMode.BYPASS_LAN.name,
    val customRoutingRules: List<CustomRoutingRule> = emptyList(),
    val dnsPreset: String = DnsPreset.CLOUDFLARE_DOH.name,
    val customDnsServer: String = "",
    val remoteDnsThroughProxy: Boolean = true,
    val autoConnect: Boolean = false,
    val autoReconnect: Boolean = true,
    val autoFailover: Boolean = false,
    val splitTunnelMode: String = SplitTunnelMode.DISABLED.name,
    val splitTunnelPackages: List<String> = emptyList(),
    val hideInRecents: Boolean = false,
    val disguiseNotification: Boolean = false,
    val expiryWarningEnabled: Boolean = true,
    val lowTrafficWarningEnabled: Boolean = true,
    val subscriptionUrlOptional: String? = null
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val customRules = prefs[KEY_CUSTOM_ROUTING_RULES]?.let { raw ->
            runCatching { json.decodeFromString<List<CustomRoutingRule>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

        val customPingUrls = prefs[KEY_CUSTOM_PING_URLS]?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

        AppSettings(
            onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false,
            coachTipsDismissed = prefs[KEY_COACH_TIPS_DISMISSED] ?: false,
            language = AppLanguage.fromCode(prefs[KEY_LANGUAGE] ?: BrandConfig.defaultLanguageCode),
            persianDigitsEnabled = prefs[KEY_PERSIAN_DIGITS] ?: true,
            calendarSystem = enumOrDefault(prefs[KEY_CALENDAR_SYSTEM], CalendarSystem.JALALI),
            themeMode = enumOrDefault(prefs[KEY_THEME_MODE], ThemeMode.DARK),
            themePalette = enumOrDefault(prefs[KEY_THEME_PALETTE], ThemePalette.OCEAN),
            dynamicColorEnabled = prefs[KEY_DYNAMIC_COLOR] ?: false,

            corePreference = enumOrDefault(prefs[KEY_CORE_PREF], CorePreference.AUTO),
            subscriptionFormatStrategy = enumOrDefault(
                prefs[KEY_SUB_FORMAT_STRATEGY],
                SubscriptionFormatStrategy.PREFER_XRAY_JSON
            ),
            autoRefreshIntervalHours = prefs[KEY_AUTO_REFRESH_HOURS] ?: 6,
            dismissedAnnouncementHash = prefs[KEY_DISMISSED_ANNOUNCE_HASH] ?: "",

            pingTestMode = enumOrDefault(prefs[KEY_PING_MODE], PingTestMode.THROUGH_CORE),
            pingTestUrl = prefs[KEY_PING_URL] ?: PingScoring.DEFAULT_TEST_URLS.first(),
            customPingUrls = customPingUrls,
            pingSamplesPerServer = (prefs[KEY_PING_SAMPLES] ?: 2).coerceIn(1, 5),
            pingTimeoutSec = (prefs[KEY_PING_TIMEOUT_SEC] ?: 5).coerceIn(2, 10),
            pingMaxConcurrency = (prefs[KEY_PING_CONCURRENCY] ?: 5).coerceIn(1, 10),
            autoSelectMode = enumOrDefault(prefs[KEY_AUTO_SELECT_MODE], AutoSelectMode.LOWEST_PING),
            autoTestOnAppOpen = prefs[KEY_AUTO_TEST_APP_OPEN] ?: false,
            autoTestBeforeAutoSelect = prefs[KEY_AUTO_TEST_BEFORE_SELECT] ?: true,
            hideDeadServers = prefs[KEY_HIDE_DEAD] ?: false,
            serverSortMode = enumOrDefault(prefs[KEY_SERVER_SORT], ServerSortMode.PING),

            ipInfoEndpoint = prefs[KEY_IP_INFO_URL] ?: "https://ipwho.is/",
            speedTestUrl = prefs[KEY_SPEED_TEST_URL] ?: "https://speed.cloudflare.com/__down?bytes=10000000",
            updateCheckUrl = prefs[KEY_UPDATE_CHECK_URL] ?: "",

            mtu = (prefs[KEY_MTU] ?: 1500).coerceIn(1280, 9000),
            enableIpv6 = prefs[KEY_ENABLE_IPV6] ?: false,
            enableFragment = prefs[KEY_ENABLE_FRAGMENT] ?: false,
            enableMux = prefs[KEY_ENABLE_MUX] ?: false,
            enableSniffing = prefs[KEY_ENABLE_SNIFFING] ?: true,
            allowInsecureGlobal = prefs[KEY_ALLOW_INSECURE] ?: false,
            routingMode = enumOrDefault(prefs[KEY_ROUTING_MODE], RoutingMode.BYPASS_LAN),
            customRoutingRules = customRules,
            dnsPreset = enumOrDefault(prefs[KEY_DNS_PRESET], DnsPreset.CLOUDFLARE_DOH),
            customDnsServer = prefs[KEY_CUSTOM_DNS] ?: "",
            remoteDnsThroughProxy = prefs[KEY_REMOTE_DNS_PROXY] ?: true,

            autoConnect = prefs[KEY_AUTO_CONNECT] ?: false,
            networkAutoConnectRule = enumOrDefault(prefs[KEY_NET_AUTO_RULE], NetworkAutoConnectRule.NONE),
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
            autoFailover = prefs[KEY_AUTO_FAILOVER] ?: false,
            killSwitchEnabled = prefs[KEY_KILL_SWITCH] ?: false,
            blockTrafficOnDrop = prefs[KEY_BLOCK_ON_DROP] ?: false,
            splitTunnelMode = enumOrDefault(prefs[KEY_SPLIT_MODE], SplitTunnelMode.DISABLED),
            splitTunnelPackages = prefs[KEY_SPLIT_PACKAGES] ?: emptySet(),
            selectedServerId = prefs[KEY_SELECTED_SERVER_ID],

            appLockEnabled = prefs[KEY_APP_LOCK_ENABLED] ?: false,
            appLockPin = prefs[KEY_APP_LOCK_PIN] ?: "",
            hideInRecents = prefs[KEY_HIDE_IN_RECENTS] ?: false,
            disguiseNotification = prefs[KEY_DISGUISE_NOTIF] ?: false,

            expiryWarningEnabled = prefs[KEY_EXPIRY_WARNING] ?: true,
            lowTrafficWarningEnabled = prefs[KEY_LOW_TRAFFIC_WARNING] ?: true
        )
    }

    override suspend fun getSnapshot(): AppSettings = settings.first()

    override suspend fun setOnboardingCompleted(completed: Boolean) = edit { it[KEY_ONBOARDING_DONE] = completed }
    override suspend fun setCoachTipsDismissed(dismissed: Boolean) = edit { it[KEY_COACH_TIPS_DISMISSED] = dismissed }
    override suspend fun setLanguage(language: AppLanguage) = edit { it[KEY_LANGUAGE] = language.code }
    override suspend fun setPersianDigitsEnabled(enabled: Boolean) = edit { it[KEY_PERSIAN_DIGITS] = enabled }
    override suspend fun setCalendarSystem(system: CalendarSystem) = edit { it[KEY_CALENDAR_SYSTEM] = system.name }
    override suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.name }

    override suspend fun setThemePalette(palette: ThemePalette) = edit {
        it[KEY_THEME_PALETTE] = palette.name
        it[KEY_DYNAMIC_COLOR] = false
    }
    override suspend fun setDynamicColorEnabled(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }
    override suspend fun setCorePreference(preference: CorePreference) = edit { it[KEY_CORE_PREF] = preference.name }
    override suspend fun setSubscriptionFormatStrategy(strategy: SubscriptionFormatStrategy) = edit { it[KEY_SUB_FORMAT_STRATEGY] = strategy.name }
    override suspend fun setAutoRefreshIntervalHours(hours: Int) = edit { it[KEY_AUTO_REFRESH_HOURS] = hours }
    override suspend fun setDismissedAnnouncementHash(hash: String) = edit { it[KEY_DISMISSED_ANNOUNCE_HASH] = hash }

    override suspend fun setPingTestMode(mode: PingTestMode) = edit { it[KEY_PING_MODE] = mode.name }
    override suspend fun setPingTestUrl(url: String) = edit { it[KEY_PING_URL] = url.trim() }
    override suspend fun setCustomPingUrls(urls: List<String>) = edit { it[KEY_CUSTOM_PING_URLS] = json.encodeToString(urls) }
    override suspend fun setPingSamplesPerServer(count: Int) = edit { it[KEY_PING_SAMPLES] = count.coerceIn(1, 5) }
    override suspend fun setPingTimeoutSec(seconds: Int) = edit { it[KEY_PING_TIMEOUT_SEC] = seconds.coerceIn(2, 10) }
    override suspend fun setPingMaxConcurrency(concurrency: Int) = edit { it[KEY_PING_CONCURRENCY] = concurrency.coerceIn(1, 10) }
    override suspend fun setAutoSelectMode(mode: AutoSelectMode) = edit { it[KEY_AUTO_SELECT_MODE] = mode.name }
    override suspend fun setAutoTestOnAppOpen(enabled: Boolean) = edit { it[KEY_AUTO_TEST_APP_OPEN] = enabled }
    override suspend fun setAutoTestBeforeAutoSelect(enabled: Boolean) = edit { it[KEY_AUTO_TEST_BEFORE_SELECT] = enabled }
    override suspend fun setHideDeadServers(hide: Boolean) = edit { it[KEY_HIDE_DEAD] = hide }
    override suspend fun setServerSortMode(mode: ServerSortMode) = edit { it[KEY_SERVER_SORT] = mode.name }

    override suspend fun setIpInfoEndpoint(url: String) = edit { it[KEY_IP_INFO_URL] = url.trim() }
    override suspend fun setSpeedTestUrl(url: String) = edit { it[KEY_SPEED_TEST_URL] = url.trim() }
    override suspend fun setUpdateCheckUrl(url: String) = edit { it[KEY_UPDATE_CHECK_URL] = url.trim() }

    override suspend fun setMtu(mtu: Int) = edit { it[KEY_MTU] = mtu.coerceIn(1280, 9000) }
    override suspend fun setEnableIpv6(enabled: Boolean) = edit { it[KEY_ENABLE_IPV6] = enabled }
    override suspend fun setEnableFragment(enabled: Boolean) = edit { it[KEY_ENABLE_FRAGMENT] = enabled }
    override suspend fun setEnableMux(enabled: Boolean) = edit { it[KEY_ENABLE_MUX] = enabled }
    override suspend fun setEnableSniffing(enabled: Boolean) = edit { it[KEY_ENABLE_SNIFFING] = enabled }
    override suspend fun setAllowInsecureGlobal(enabled: Boolean) = edit { it[KEY_ALLOW_INSECURE] = enabled }
    override suspend fun setRoutingMode(mode: RoutingMode) = edit { it[KEY_ROUTING_MODE] = mode.name }
    override suspend fun setCustomRoutingRules(rules: List<CustomRoutingRule>) = edit { it[KEY_CUSTOM_ROUTING_RULES] = json.encodeToString(rules) }
    override suspend fun setDnsPreset(preset: DnsPreset) = edit { it[KEY_DNS_PRESET] = preset.name }
    override suspend fun setCustomDnsServer(server: String) = edit { it[KEY_CUSTOM_DNS] = server.trim() }
    override suspend fun setRemoteDnsThroughProxy(enabled: Boolean) = edit { it[KEY_REMOTE_DNS_PROXY] = enabled }

    override suspend fun setAutoConnect(enabled: Boolean) = edit { it[KEY_AUTO_CONNECT] = enabled }
    override suspend fun setNetworkAutoConnectRule(rule: NetworkAutoConnectRule) = edit { it[KEY_NET_AUTO_RULE] = rule.name }
    override suspend fun setAutoReconnect(enabled: Boolean) = edit { it[KEY_AUTO_RECONNECT] = enabled }
    override suspend fun setAutoFailover(enabled: Boolean) = edit { it[KEY_AUTO_FAILOVER] = enabled }
    override suspend fun setKillSwitchEnabled(enabled: Boolean) = edit { it[KEY_KILL_SWITCH] = enabled }
    override suspend fun setBlockTrafficOnDrop(enabled: Boolean) = edit { it[KEY_BLOCK_ON_DROP] = enabled }
    override suspend fun setSplitTunnelMode(mode: SplitTunnelMode) = edit { it[KEY_SPLIT_MODE] = mode.name }
    override suspend fun setSplitTunnelPackages(packages: Set<String>) = edit { it[KEY_SPLIT_PACKAGES] = packages }
    override suspend fun setSelectedServerId(serverId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (serverId == null) prefs.remove(KEY_SELECTED_SERVER_ID)
            else prefs[KEY_SELECTED_SERVER_ID] = serverId
        }
    }

    override suspend fun setAppLockEnabled(enabled: Boolean) = edit { it[KEY_APP_LOCK_ENABLED] = enabled }
    override suspend fun setAppLockPin(pin: String) = edit { it[KEY_APP_LOCK_PIN] = pin }
    override suspend fun setHideInRecents(hide: Boolean) = edit { it[KEY_HIDE_IN_RECENTS] = hide }
    override suspend fun setDisguiseNotification(disguise: Boolean) = edit { it[KEY_DISGUISE_NOTIF] = disguise }

    override suspend fun setExpiryWarningEnabled(enabled: Boolean) = edit { it[KEY_EXPIRY_WARNING] = enabled }
    override suspend fun setLowTrafficWarningEnabled(enabled: Boolean) = edit { it[KEY_LOW_TRAFFIC_WARNING] = enabled }

    override suspend fun exportSettingsJson(includeToken: Boolean, tokenOrNull: String?): String {
        val s = getSnapshot()
        val payload = SettingsBackupPayload(
            language = s.language.code,
            persianDigitsEnabled = s.persianDigitsEnabled,
            calendarSystem = s.calendarSystem.name,
            themeMode = s.themeMode.name,
            themePalette = s.themePalette.name,
            dynamicColorEnabled = s.dynamicColorEnabled,
            corePreference = s.corePreference.name,
            subscriptionFormatStrategy = s.subscriptionFormatStrategy.name,
            autoRefreshIntervalHours = s.autoRefreshIntervalHours,
            pingTestMode = s.pingTestMode.name,
            pingTestUrl = s.pingTestUrl,
            customPingUrls = s.customPingUrls,
            pingSamplesPerServer = s.pingSamplesPerServer,
            pingTimeoutSec = s.pingTimeoutSec,
            pingMaxConcurrency = s.pingMaxConcurrency,
            autoSelectMode = s.autoSelectMode.name,
            autoTestOnAppOpen = s.autoTestOnAppOpen,
            autoTestBeforeAutoSelect = s.autoTestBeforeAutoSelect,
            hideDeadServers = s.hideDeadServers,
            serverSortMode = s.serverSortMode.name,
            mtu = s.mtu,
            enableIpv6 = s.enableIpv6,
            enableFragment = s.enableFragment,
            enableMux = s.enableMux,
            enableSniffing = s.enableSniffing,
            allowInsecureGlobal = s.allowInsecureGlobal,
            routingMode = s.routingMode.name,
            customRoutingRules = s.customRoutingRules,
            dnsPreset = s.dnsPreset.name,
            customDnsServer = s.customDnsServer,
            remoteDnsThroughProxy = s.remoteDnsThroughProxy,
            autoConnect = s.autoConnect,
            autoReconnect = s.autoReconnect,
            autoFailover = s.autoFailover,
            splitTunnelMode = s.splitTunnelMode.name,
            splitTunnelPackages = s.splitTunnelPackages.toList(),
            hideInRecents = s.hideInRecents,
            disguiseNotification = s.disguiseNotification,
            expiryWarningEnabled = s.expiryWarningEnabled,
            lowTrafficWarningEnabled = s.lowTrafficWarningEnabled,
            subscriptionUrlOptional = if (includeToken) tokenOrNull else null
        )
        return json.encodeToString(payload)
    }

    override suspend fun importSettingsJson(rawJson: String): Result<String?> = runCatching {
        val p = json.decodeFromString<SettingsBackupPayload>(rawJson)
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = p.language
            prefs[KEY_PERSIAN_DIGITS] = p.persianDigitsEnabled
            prefs[KEY_CALENDAR_SYSTEM] = p.calendarSystem
            prefs[KEY_THEME_MODE] = p.themeMode
            prefs[KEY_THEME_PALETTE] = p.themePalette
            prefs[KEY_DYNAMIC_COLOR] = p.dynamicColorEnabled
            prefs[KEY_CORE_PREF] = p.corePreference
            prefs[KEY_SUB_FORMAT_STRATEGY] = p.subscriptionFormatStrategy
            prefs[KEY_AUTO_REFRESH_HOURS] = p.autoRefreshIntervalHours
            prefs[KEY_PING_MODE] = p.pingTestMode
            prefs[KEY_PING_URL] = p.pingTestUrl
            prefs[KEY_CUSTOM_PING_URLS] = json.encodeToString(p.customPingUrls)
            prefs[KEY_PING_SAMPLES] = p.pingSamplesPerServer.coerceIn(1, 5)
            prefs[KEY_PING_TIMEOUT_SEC] = p.pingTimeoutSec.coerceIn(2, 10)
            prefs[KEY_PING_CONCURRENCY] = p.pingMaxConcurrency.coerceIn(1, 10)
            prefs[KEY_AUTO_SELECT_MODE] = p.autoSelectMode
            prefs[KEY_AUTO_TEST_APP_OPEN] = p.autoTestOnAppOpen
            prefs[KEY_AUTO_TEST_BEFORE_SELECT] = p.autoTestBeforeAutoSelect
            prefs[KEY_HIDE_DEAD] = p.hideDeadServers
            prefs[KEY_SERVER_SORT] = p.serverSortMode
            prefs[KEY_MTU] = p.mtu.coerceIn(1280, 9000)
            prefs[KEY_ENABLE_IPV6] = p.enableIpv6
            prefs[KEY_ENABLE_FRAGMENT] = p.enableFragment
            prefs[KEY_ENABLE_MUX] = p.enableMux
            prefs[KEY_ENABLE_SNIFFING] = p.enableSniffing
            prefs[KEY_ALLOW_INSECURE] = p.allowInsecureGlobal
            prefs[KEY_ROUTING_MODE] = p.routingMode
            prefs[KEY_CUSTOM_ROUTING_RULES] = json.encodeToString(p.customRoutingRules)
            prefs[KEY_DNS_PRESET] = p.dnsPreset
            prefs[KEY_CUSTOM_DNS] = p.customDnsServer
            prefs[KEY_REMOTE_DNS_PROXY] = p.remoteDnsThroughProxy
            prefs[KEY_AUTO_CONNECT] = p.autoConnect
            prefs[KEY_AUTO_RECONNECT] = p.autoReconnect
            prefs[KEY_AUTO_FAILOVER] = p.autoFailover
            prefs[KEY_SPLIT_MODE] = p.splitTunnelMode
            prefs[KEY_SPLIT_PACKAGES] = p.splitTunnelPackages.toSet()
            prefs[KEY_HIDE_IN_RECENTS] = p.hideInRecents
            prefs[KEY_DISGUISE_NOTIF] = p.disguiseNotification
            prefs[KEY_EXPIRY_WARNING] = p.expiryWarningEnabled
            prefs[KEY_LOW_TRAFFIC_WARNING] = p.lowTrafficWarningEnabled
        }
        p.subscriptionUrlOptional
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_COACH_TIPS_DISMISSED = booleanPreferencesKey("coach_tips_dismissed")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_PERSIAN_DIGITS = booleanPreferencesKey("persian_digits")
        private val KEY_CALENDAR_SYSTEM = stringPreferencesKey("calendar_system")
        private val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_CORE_PREF = stringPreferencesKey("core_preference")
        private val KEY_SUB_FORMAT_STRATEGY = stringPreferencesKey("sub_format_strategy")
        private val KEY_AUTO_REFRESH_HOURS = intPreferencesKey("auto_refresh_hours")
        private val KEY_DISMISSED_ANNOUNCE_HASH = stringPreferencesKey("dismissed_announce_hash")

        private val KEY_PING_MODE = stringPreferencesKey("ping_mode")
        private val KEY_PING_URL = stringPreferencesKey("ping_url")
        private val KEY_CUSTOM_PING_URLS = stringPreferencesKey("custom_ping_urls")
        private val KEY_PING_SAMPLES = intPreferencesKey("ping_samples")
        private val KEY_PING_TIMEOUT_SEC = intPreferencesKey("ping_timeout_sec")
        private val KEY_PING_CONCURRENCY = intPreferencesKey("ping_concurrency")
        private val KEY_AUTO_SELECT_MODE = stringPreferencesKey("auto_select_mode")
        private val KEY_AUTO_TEST_APP_OPEN = booleanPreferencesKey("auto_test_app_open")
        private val KEY_AUTO_TEST_BEFORE_SELECT = booleanPreferencesKey("auto_test_before_select")
        private val KEY_HIDE_DEAD = booleanPreferencesKey("hide_dead_servers")
        private val KEY_SERVER_SORT = stringPreferencesKey("server_sort_mode")

        private val KEY_IP_INFO_URL = stringPreferencesKey("ip_info_url")
        private val KEY_SPEED_TEST_URL = stringPreferencesKey("speed_test_url")
        private val KEY_UPDATE_CHECK_URL = stringPreferencesKey("update_check_url")

        private val KEY_MTU = intPreferencesKey("vpn_mtu")
        private val KEY_ENABLE_IPV6 = booleanPreferencesKey("enable_ipv6")
        private val KEY_ENABLE_FRAGMENT = booleanPreferencesKey("enable_fragment")
        private val KEY_ENABLE_MUX = booleanPreferencesKey("enable_mux")
        private val KEY_ENABLE_SNIFFING = booleanPreferencesKey("enable_sniffing")
        private val KEY_ALLOW_INSECURE = booleanPreferencesKey("allow_insecure_global")
        private val KEY_ROUTING_MODE = stringPreferencesKey("routing_mode")
        private val KEY_CUSTOM_ROUTING_RULES = stringPreferencesKey("custom_routing_rules")
        private val KEY_DNS_PRESET = stringPreferencesKey("dns_preset")
        private val KEY_CUSTOM_DNS = stringPreferencesKey("custom_dns_server")
        private val KEY_REMOTE_DNS_PROXY = booleanPreferencesKey("remote_dns_proxy")

        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_NET_AUTO_RULE = stringPreferencesKey("net_auto_rule")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_AUTO_FAILOVER = booleanPreferencesKey("auto_failover")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEY_BLOCK_ON_DROP = booleanPreferencesKey("block_traffic_on_drop")
        private val KEY_SPLIT_MODE = stringPreferencesKey("split_tunnel_mode")
        private val KEY_SPLIT_PACKAGES = stringSetPreferencesKey("split_tunnel_packages")
        private val KEY_SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")

        private val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val KEY_APP_LOCK_PIN = stringPreferencesKey("app_lock_pin")
        private val KEY_HIDE_IN_RECENTS = booleanPreferencesKey("hide_in_recents")
        private val KEY_DISGUISE_NOTIF = booleanPreferencesKey("disguise_notif")

        private val KEY_EXPIRY_WARNING = booleanPreferencesKey("expiry_warning")
        private val KEY_LOW_TRAFFIC_WARNING = booleanPreferencesKey("low_traffic_warning")
    }
}
