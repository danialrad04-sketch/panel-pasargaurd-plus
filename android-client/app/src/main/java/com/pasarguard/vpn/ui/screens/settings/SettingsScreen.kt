package com.pasarguard.vpn.ui.screens.settings

import com.pasarguard.vpn.ui.components.PasarSheetHandle
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import com.pasarguard.vpn.ui.components.PasarDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.CalendarSystem
import com.pasarguard.vpn.domain.model.CorePreference
import com.pasarguard.vpn.domain.model.DnsPreset
import com.pasarguard.vpn.domain.model.NetworkAutoConnectRule
import com.pasarguard.vpn.domain.model.SubscriptionFormatStrategy
import com.pasarguard.vpn.domain.model.ThemePalette
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.usecase.LogoutUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.ui.theme.tokens
import com.pasarguard.vpn.ui.updates.LocalCheckForUpdates
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.vpn.core.XrayCore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logoutUseCase: LogoutUseCase,
    private val xrayCore: XrayCore
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    val xrayVersion: String by lazy { xrayCore.versionInfo() }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
            Formatters.applyAppLanguage(language)
        }
    }

    fun setPersianDigits(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPersianDigitsEnabled(enabled) }
    }

    fun setCalendarSystem(system: CalendarSystem) {
        viewModelScope.launch { settingsRepository.setCalendarSystem(system) }
    }

    fun setThemePalette(palette: ThemePalette) {
        viewModelScope.launch { settingsRepository.setThemePalette(palette) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(enabled) }
    }

    fun setCorePreference(preference: CorePreference) {
        viewModelScope.launch { settingsRepository.setCorePreference(preference) }
    }

    fun setSubscriptionFormatStrategy(strategy: SubscriptionFormatStrategy) {
        viewModelScope.launch { settingsRepository.setSubscriptionFormatStrategy(strategy) }
    }

    fun setDnsPreset(preset: DnsPreset) {
        viewModelScope.launch { settingsRepository.setDnsPreset(preset) }
    }

    fun setCustomDnsServer(dns: String) {
        viewModelScope.launch { settingsRepository.setCustomDnsServer(dns) }
    }

    fun setRemoteDnsThroughProxy(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRemoteDnsThroughProxy(enabled) }
    }

    fun setMtu(mtu: Int) {
        viewModelScope.launch { settingsRepository.setMtu(mtu) }
    }

    fun setEnableIpv6(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableIpv6(enabled) }
    }

    fun setEnableFragment(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableFragment(enabled) }
    }

    fun setEnableMux(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableMux(enabled) }
    }

    fun setEnableSniffing(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnableSniffing(enabled) }
    }

    fun setAllowInsecureGlobal(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAllowInsecureGlobal(enabled) }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoConnect(enabled) }
    }

    fun setNetworkAutoConnectRule(rule: NetworkAutoConnectRule) {
        viewModelScope.launch { settingsRepository.setNetworkAutoConnectRule(rule) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoReconnect(enabled) }
    }

    fun setAutoFailover(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoFailover(enabled) }
    }

    fun setAutoTestOnAppOpen(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestOnAppOpen(enabled) }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKillSwitchEnabled(enabled) }
    }

    fun setBlockTrafficOnDrop(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBlockTrafficOnDrop(enabled) }
    }

    fun setAppLock(enabled: Boolean, pin: String) {
        viewModelScope.launch {
            settingsRepository.setAppLockEnabled(enabled)
            settingsRepository.setAppLockPin(pin)
        }
    }

    fun setHideInRecents(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHideInRecents(enabled) }
    }

    fun setDisguiseNotification(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDisguiseNotification(enabled) }
    }

    fun setExpiryAlert(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setExpiryWarningEnabled(enabled) }
    }

    fun setLowTrafficAlert(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLowTrafficWarningEnabled(enabled) }
    }

    fun exportBackupJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            onReady(settingsRepository.exportSettingsJson(includeToken = false, tokenOrNull = null))
        }
    }

    fun importBackupJson(jsonText: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val res = settingsRepository.importSettingsJson(jsonText)
            onDone(res.isSuccess)
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            logoutUseCase()
            onComplete()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenSplitTunneling: () -> Unit,
    onOpenRoutingRules: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val checkForUpdates = LocalCheckForUpdates.current
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current
    val context = LocalContext.current

    var showLogoutSheet by remember { mutableStateOf(false) }
    var showAllowInsecureWarning by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var customDnsInput by remember(settings.customDnsServer) { mutableStateOf(settings.customDnsServer) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingExportJson
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
            }.onSuccess {
                Toast.makeText(context, R.string.settings_backup_saved, Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportJson = null
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { text ->
                viewModel.importBackupJson(text) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) R.string.settings_backup_restored else R.string.settings_backup_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary
            )

            // Appearance, Persian & Calendar
            SectionLabel(stringResource(R.string.settings_section_general))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = settings.language == AppLanguage.PERSIAN,
                        onClick = { viewModel.setLanguage(AppLanguage.PERSIAN) },
                        label = { Text(stringResource(R.string.settings_lang_fa)) }
                    )
                    FilterChip(
                        selected = settings.language == AppLanguage.ENGLISH,
                        onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                        label = { Text(stringResource(R.string.settings_lang_en)) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                SettingsToggleRow(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.settings_persian_digits),
                    subtitle = null,
                    checked = settings.persianDigitsEnabled,
                    onCheckedChange = viewModel::setPersianDigits
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_calendar), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                }
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = settings.calendarSystem == CalendarSystem.JALALI,
                        onClick = { viewModel.setCalendarSystem(CalendarSystem.JALALI) },
                        label = { Text(stringResource(R.string.settings_calendar_jalali)) }
                    )
                    FilterChip(
                        selected = settings.calendarSystem == CalendarSystem.GREGORIAN,
                        onClick = { viewModel.setCalendarSystem(CalendarSystem.GREGORIAN) },
                        label = { Text(stringResource(R.string.settings_calendar_gregorian)) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                }
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(
                        ThemeMode.DARK to R.string.settings_theme_dark,
                        ThemeMode.LIGHT to R.string.settings_theme_light,
                        ThemeMode.SYSTEM to R.string.settings_theme_system
                    ).forEach { (mode, resId) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(stringResource(resId)) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                Text(stringResource(R.string.settings_palette), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(
                        ThemePalette.OCEAN to R.string.settings_palette_ocean,
                        ThemePalette.JADE to R.string.settings_palette_jade,
                        ThemePalette.DUSK to R.string.settings_palette_dusk
                    ).forEach { (palette, label) ->
                        val swatch = palette.tokens(colors.isDark).accent
                        FilterChip(
                            selected = !settings.dynamicColorEnabled && settings.themePalette == palette,
                            onClick = { viewModel.setThemePalette(palette) },
                            label = { Text(stringResource(label)) },
                            leadingIcon = {
                                Box(Modifier.size(12.dp).background(swatch, CircleShape))
                            }
                        )
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                    SettingsToggleRow(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_desc),
                        checked = settings.dynamicColorEnabled,
                        onCheckedChange = viewModel::setDynamicColor
                    )
                }
            }

            // Core, Routing, DNS & Advanced Xray
            SectionLabel(stringResource(R.string.settings_section_vpn))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(stringResource(R.string.settings_core_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Text(stringResource(R.string.settings_core_desc), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(
                        CorePreference.AUTO to R.string.settings_core_auto,
                        CorePreference.XRAY to R.string.settings_core_xray,
                        CorePreference.SING_BOX to R.string.settings_core_singbox
                    ).forEach { (pref, resId) ->
                        FilterChip(
                            selected = settings.corePreference == pref,
                            onClick = { viewModel.setCorePreference(pref) },
                            label = { Text(stringResource(resId)) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(stringResource(R.string.settings_sub_format_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Text(stringResource(R.string.settings_sub_format_desc), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(
                        SubscriptionFormatStrategy.PREFER_XRAY_JSON to R.string.settings_sub_format_xray_json,
                        SubscriptionFormatStrategy.LINKS_ONLY to R.string.settings_sub_format_links_only
                    ).forEach { (strategy, resId) ->
                        FilterChip(
                            selected = settings.subscriptionFormatStrategy == strategy,
                            onClick = { viewModel.setSubscriptionFormatStrategy(strategy) },
                            label = { Text(stringResource(resId)) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                SettingsActionRow(
                    icon = Icons.Rounded.AltRoute,
                    title = stringResource(R.string.routing_title),
                    subtitle = settings.routingMode.name,
                    onClick = onOpenRoutingRules
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                // DNS Presets
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_dns_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                }
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    DnsPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = settings.dnsPreset == preset,
                            onClick = { viewModel.setDnsPreset(preset) },
                            label = { Text(preset.label) }
                        )
                    }
                }
                if (settings.dnsPreset == DnsPreset.CUSTOM) {
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = customDnsInput,
                        onValueChange = {
                            customDnsInput = it
                            viewModel.setCustomDnsServer(it)
                        },
                        placeholder = { Text("https://1.1.1.1/dns-query") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SettingsToggleRow(
                    icon = Icons.Rounded.Public,
                    title = stringResource(R.string.settings_remote_dns_proxy),
                    subtitle = null,
                    checked = settings.remoteDnsThroughProxy,
                    onCheckedChange = viewModel::setRemoteDnsThroughProxy
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)

                // MTU & Advanced Xray Options
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_mtu_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                }
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(1280, 1400, 1500).forEach { mtuVal ->
                        FilterChip(
                            selected = settings.mtu == mtuVal,
                            onClick = { viewModel.setMtu(mtuVal) },
                            label = { Text(Formatters.toPersianDigitsIfNeeded("$mtuVal", persianDigits)) }
                        )
                    }
                }

                SettingsToggleRow(
                    icon = Icons.Rounded.Public,
                    title = stringResource(R.string.settings_ipv6_title),
                    subtitle = stringResource(R.string.settings_ipv6_desc),
                    checked = settings.enableIpv6,
                    onCheckedChange = viewModel::setEnableIpv6
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_fragment_title),
                    subtitle = stringResource(R.string.settings_fragment_desc),
                    checked = settings.enableFragment,
                    onCheckedChange = viewModel::setEnableFragment
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_mux_title),
                    subtitle = stringResource(R.string.settings_mux_desc),
                    checked = settings.enableMux,
                    onCheckedChange = viewModel::setEnableMux
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_sniffing_title),
                    subtitle = null,
                    checked = settings.enableSniffing,
                    onCheckedChange = viewModel::setEnableSniffing
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.settings_allow_insecure_title),
                    subtitle = stringResource(R.string.settings_allow_insecure_desc),
                    checked = settings.allowInsecureGlobal,
                    onCheckedChange = { want ->
                        if (want) showAllowInsecureWarning = true
                        else viewModel.setAllowInsecureGlobal(false)
                    }
                )
            }

            // Automation, Split Tunneling & Kill Switch
            SectionLabel(stringResource(R.string.settings_section_automation))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                SettingsActionRow(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.settings_split_tunnel),
                    subtitle = stringResource(R.string.settings_split_tunnel_desc),
                    onClick = onOpenSplitTunneling
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsToggleRow(
                    icon = Icons.Rounded.Power,
                    title = stringResource(R.string.settings_auto_connect),
                    subtitle = stringResource(R.string.settings_auto_connect_desc),
                    checked = settings.autoConnect,
                    onCheckedChange = viewModel::setAutoConnect
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.Power,
                    title = stringResource(R.string.settings_auto_reconnect),
                    subtitle = stringResource(R.string.settings_auto_reconnect_desc),
                    checked = settings.autoReconnect,
                    onCheckedChange = viewModel::setAutoReconnect
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.Power,
                    title = stringResource(R.string.settings_auto_failover),
                    subtitle = stringResource(R.string.settings_auto_failover_desc),
                    checked = settings.autoFailover,
                    onCheckedChange = viewModel::setAutoFailover
                )
                SettingsToggleRow(
                    icon = Icons.Rounded.NetworkCheck,
                    title = stringResource(R.string.settings_auto_test_open),
                    subtitle = null,
                    checked = settings.autoTestOnAppOpen,
                    onCheckedChange = viewModel::setAutoTestOnAppOpen
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                Text(stringResource(R.string.settings_network_rule), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(
                        NetworkAutoConnectRule.NONE to R.string.split_mode_disabled,
                        NetworkAutoConnectRule.UNTRUSTED_WIFI to R.string.settings_net_wifi,
                        NetworkAutoConnectRule.MOBILE_DATA to R.string.settings_net_mobile,
                        NetworkAutoConnectRule.ALWAYS to R.string.settings_net_always
                    ).forEach { (rule, resId) ->
                        FilterChip(
                            selected = settings.networkAutoConnectRule == rule,
                            onClick = { viewModel.setNetworkAutoConnectRule(rule) },
                            label = { Text(stringResource(resId)) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsToggleRow(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.settings_block_on_drop),
                    subtitle = null,
                    checked = settings.blockTrafficOnDrop,
                    onCheckedChange = viewModel::setBlockTrafficOnDrop
                )
                SettingsActionRow(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.settings_kill_switch),
                    subtitle = stringResource(R.string.settings_kill_switch_desc),
                    onClick = {
                        viewModel.setKillSwitch(true)
                        Formatters.openSystemVpnSettings(context)
                    }
                )
            }

            // Privacy & Security
            SectionLabel(stringResource(R.string.settings_section_privacy))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                SettingsToggleRow(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.app_lock_title),
                    subtitle = stringResource(R.string.app_lock_setting_desc),
                    checked = settings.appLockEnabled,
                    onCheckedChange = { enable ->
                        if (enable) showPinDialog = true
                        else viewModel.setAppLock(false, "")
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsToggleRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.settings_hide_recents),
                    subtitle = stringResource(R.string.settings_hide_recents_desc),
                    checked = settings.hideInRecents,
                    onCheckedChange = viewModel::setHideInRecents
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsToggleRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.settings_disguise_notif),
                    subtitle = stringResource(R.string.settings_disguise_notif_desc),
                    checked = settings.disguiseNotification,
                    onCheckedChange = viewModel::setDisguiseNotification
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsActionRow(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.logs_title),
                    subtitle = stringResource(R.string.logs_subtitle),
                    onClick = onOpenLogs
                )
            }

            // Pro Tools & Backup
            SectionLabel(stringResource(R.string.settings_section_tools))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                SettingsActionRow(
                    icon = Icons.Rounded.AccountCircle,
                    title = stringResource(R.string.profiles_title),
                    subtitle = null,
                    onClick = onOpenProfiles
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsActionRow(
                    icon = Icons.Rounded.NetworkCheck,
                    title = stringResource(R.string.diagnostics_title),
                    subtitle = null,
                    onClick = onOpenDiagnostics
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsActionRow(
                    icon = Icons.Rounded.BarChart,
                    title = stringResource(R.string.stats_title),
                    subtitle = null,
                    onClick = onOpenStatistics
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsActionRow(
                    icon = Icons.Rounded.Backup,
                    title = stringResource(R.string.settings_export_backup),
                    subtitle = stringResource(R.string.settings_export_backup_desc),
                    onClick = {
                        viewModel.exportBackupJson { jsonPayload ->
                            pendingExportJson = jsonPayload
                            exportLauncher.launch("pasarguard-vpn-settings.json")
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsActionRow(
                    icon = Icons.Rounded.Restore,
                    title = stringResource(R.string.settings_import_backup),
                    subtitle = null,
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                checkForUpdates?.let {
                    SettingsActionRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = stringResource(R.string.settings_check_updates),
                        subtitle = "v${BuildConfig.VERSION_NAME}",
                        onClick = it
                    )
                }
            }

            // Alerts & Notifications
            SectionLabel(stringResource(R.string.settings_section_notifications))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                SettingsToggleRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.settings_expiry_alert),
                    subtitle = null,
                    checked = settings.expiryWarningEnabled,
                    onCheckedChange = viewModel::setExpiryAlert
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsToggleRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.settings_low_traffic_alert),
                    subtitle = null,
                    checked = settings.lowTrafficWarningEnabled,
                    onCheckedChange = viewModel::setLowTrafficAlert
                )
            }

            // Support & Version
            SectionLabel(stringResource(R.string.settings_section_support))
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                SettingsActionRow(
                    icon = Icons.AutoMirrored.Rounded.Send,
                    title = stringResource(R.string.settings_telegram),
                    subtitle = BrandConfig.supportTelegramUrl,
                    onClick = { Formatters.openExternalUrl(context, BrandConfig.supportTelegramUrl) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                SettingsActionRow(
                    icon = Icons.Rounded.SupportAgent,
                    title = stringResource(R.string.settings_whatsapp),
                    subtitle = BrandConfig.supportWhatsappUrl,
                    onClick = { Formatters.openExternalUrl(context, BrandConfig.supportWhatsappUrl) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = colors.glassBorderTop)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.heroEnd)
                        Spacer(Modifier.width(Spacing.md))
                        Column {
                            Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            Text(viewModel.xrayVersion, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    }
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }

            OutlinedButton(
                onClick = { showLogoutSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.statusDanger)
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.settings_logout))
            }
        }
    }

    // Allow Insecure Global Warning Dialog
    if (showAllowInsecureWarning) {
        PasarDialog(
            onDismissRequest = { showAllowInsecureWarning = false },
            title = { Text(stringResource(R.string.settings_allow_insecure_title)) },
            text = { Text(stringResource(R.string.settings_allow_insecure_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setAllowInsecureGlobal(true)
                    showAllowInsecureWarning = false
                }) { Text(stringResource(R.string.action_confirm), color = colors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showAllowInsecureWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // App Lock PIN Setup Dialog
    if (showPinDialog) {
        var pinInput by remember { mutableStateOf("") }
        PasarDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(stringResource(R.string.app_lock_set_pin_title)) },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pinInput = it },
                    label = { Text(stringResource(R.string.app_lock_pin_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pinInput.length >= 4,
                    onClick = {
                        viewModel.setAppLock(true, pinInput)
                        showPinDialog = false
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showLogoutSheet) {
        ModalBottomSheet(dragHandle = { PasarSheetHandle() },
            onDismissRequest = { showLogoutSheet = false },
            containerColor = colors.backgroundCanvas,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(stringResource(R.string.settings_logout_title), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                Text(stringResource(R.string.settings_logout_desc), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = { showLogoutSheet = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = {
                            showLogoutSheet = false
                            viewModel.logout(onLoggedOut)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.statusDanger)
                    ) { Text(stringResource(R.string.action_confirm), color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = PasarGuardTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Spacing.xs)
    )
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.heroEnd)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.textSecondary)
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = PasarGuardTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.heroEnd)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
