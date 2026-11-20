package com.pasarguard.vpn.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pasarguard_settings"
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            onboardingCompleted = prefs[KEY_ONBOARDING_DONE] ?: false,
            language = AppLanguage.fromCode(prefs[KEY_LANGUAGE] ?: BrandConfig.defaultLanguageCode),
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[KEY_THEME_MODE] ?: ThemeMode.DARK.name)
            }.getOrDefault(ThemeMode.DARK),
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: false,
            killSwitchEnabled = prefs[KEY_KILL_SWITCH] ?: false,
            splitTunnelMode = runCatching {
                SplitTunnelMode.valueOf(prefs[KEY_SPLIT_MODE] ?: SplitTunnelMode.DISABLED.name)
            }.getOrDefault(SplitTunnelMode.DISABLED),
            splitTunnelPackages = prefs[KEY_SPLIT_PACKAGES] ?: emptySet(),
            selectedServerId = prefs[KEY_SELECTED_SERVER_ID],
            expiryWarningEnabled = prefs[KEY_EXPIRY_WARNING] ?: true,
            lowTrafficWarningEnabled = prefs[KEY_LOW_TRAFFIC_WARNING] ?: true
        )
    }

    override suspend fun getSnapshot(): AppSettings = settings.first()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { it[KEY_ONBOARDING_DONE] = completed }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { it[KEY_LANGUAGE] = language.code }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setAutoConnect(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_CONNECT] = enabled }
    }

    override suspend fun setKillSwitchEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_KILL_SWITCH] = enabled }
    }

    override suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        context.settingsDataStore.edit { it[KEY_SPLIT_MODE] = mode.name }
    }

    override suspend fun setSplitTunnelPackages(packages: Set<String>) {
        context.settingsDataStore.edit { it[KEY_SPLIT_PACKAGES] = packages }
    }

    override suspend fun setSelectedServerId(serverId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (serverId == null) prefs.remove(KEY_SELECTED_SERVER_ID)
            else prefs[KEY_SELECTED_SERVER_ID] = serverId
        }
    }

    override suspend fun setExpiryWarningEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_EXPIRY_WARNING] = enabled }
    }

    override suspend fun setLowTrafficWarningEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_LOW_TRAFFIC_WARNING] = enabled }
    }

    companion object {
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEY_SPLIT_MODE = stringPreferencesKey("split_tunnel_mode")
        private val KEY_SPLIT_PACKAGES = stringSetPreferencesKey("split_tunnel_packages")
        private val KEY_SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        private val KEY_EXPIRY_WARNING = booleanPreferencesKey("expiry_warning")
        private val KEY_LOW_TRAFFIC_WARNING = booleanPreferencesKey("low_traffic_warning")
    }
}
