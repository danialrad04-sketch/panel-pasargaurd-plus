package com.pasarguard.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.CalendarSystem
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.navigation.AppNavHost
import com.pasarguard.vpn.ui.navigation.Routes
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.PasarGuardVpnTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootUiState(
    val isReady: Boolean = false,
    val startDestination: String = Routes.ONBOARDING,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColorEnabled: Boolean = false,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val persianDigitsEnabled: Boolean = true,
    val calendarSystem: CalendarSystem = CalendarSystem.JALALI,
    val hideInRecents: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockPin: String = "",
    val isLocked: Boolean = false,
    val prefilledSubUrl: String? = null,
    val paymentCallbackTimestamp: Long = 0L,
    val toggleVpnTrigger: Long = 0L,
    val openRouteTarget: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RootUiState())
    val state: StateFlow<RootUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val initialSettings = settingsRepository.getSnapshot()
            val hasCredentials = subscriptionRepository.getSavedCredentials() != null
            val start = when {
                hasCredentials -> Routes.HOME
                initialSettings.onboardingCompleted -> Routes.LOGIN
                else -> Routes.ONBOARDING
            }
            Formatters.applyAppLanguage(initialSettings.language)

            val shouldLock = initialSettings.appLockEnabled && initialSettings.appLockPin.length >= 4

            _state.update {
                it.copy(
                    isReady = true,
                    startDestination = start,
                    themeMode = initialSettings.themeMode,
                    dynamicColorEnabled = initialSettings.dynamicColorEnabled,
                    language = initialSettings.language,
                    persianDigitsEnabled = initialSettings.persianDigitsEnabled,
                    calendarSystem = initialSettings.calendarSystem,
                    hideInRecents = initialSettings.hideInRecents,
                    appLockEnabled = initialSettings.appLockEnabled,
                    appLockPin = initialSettings.appLockPin,
                    isLocked = shouldLock
                )
            }

            settingsRepository.settings.collect { updated ->
                _state.update {
                    it.copy(
                        themeMode = updated.themeMode,
                        dynamicColorEnabled = updated.dynamicColorEnabled,
                        language = updated.language,
                        persianDigitsEnabled = updated.persianDigitsEnabled,
                        calendarSystem = updated.calendarSystem,
                        hideInRecents = updated.hideInRecents,
                        appLockEnabled = updated.appLockEnabled,
                        appLockPin = updated.appLockPin
                    )
                }
            }
        }
    }

    fun unlock() {
        _state.update { it.copy(isLocked = false) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(MainActivity.EXTRA_TOGGLE_VPN, false)) {
            _state.update { it.copy(toggleVpnTrigger = System.currentTimeMillis()) }
        }
        val targetRoute = intent.getStringExtra(MainActivity.EXTRA_OPEN_ROUTE)
        if (!targetRoute.isNullOrBlank()) {
            _state.update { it.copy(openRouteTarget = targetRoute) }
        }
        val data = intent.data ?: return
        if (!data.scheme.equals(BrandConfig.paymentCallbackScheme, ignoreCase = true)) return

        when (data.host?.lowercase()) {
            "payment" -> {
                _state.update { it.copy(paymentCallbackTimestamp = System.currentTimeMillis()) }
            }

            "import" -> {
                val url = data.getQueryParameter("url") ?: data.getQueryParameter("token")
                if (!url.isNullOrBlank()) {
                    _state.update { it.copy(prefilledSubUrl = url) }
                }
            }
        }
    }

    fun consumePrefilledSubUrl() {
        _state.update { it.copy(prefilledSubUrl = null) }
    }

    fun consumeToggleVpnTrigger() {
        _state.update { it.copy(toggleVpnTrigger = 0L) }
    }

    fun consumeOpenRouteTarget() {
        _state.update { it.copy(openRouteTarget = null) }
    }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled gracefully by NotificationHelper */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashScreen.setKeepOnScreenCondition { !viewModel.state.value.isReady }
        viewModel.handleIncomingIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val rootState by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(rootState.hideInRecents) {
                if (rootState.hideInRecents) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            if (rootState.isReady) {
                PasarGuardVpnTheme(
                    themeMode = rootState.themeMode,
                    language = rootState.language,
                    dynamicColorEnabled = rootState.dynamicColorEnabled,
                    persianDigitsEnabled = rootState.persianDigitsEnabled,
                    calendarSystem = rootState.calendarSystem
                ) {
                    if (rootState.isLocked && rootState.appLockEnabled && rootState.appLockPin.length >= 4) {
                        AppLockScreen(
                            expectedPin = rootState.appLockPin,
                            onUnlocked = viewModel::unlock
                        )
                    } else {
                        AppNavHost(
                            startDestination = rootState.startDestination,
                            prefilledSubscriptionUrl = rootState.prefilledSubUrl,
                            paymentCallbackTimestamp = rootState.paymentCallbackTimestamp,
                            toggleVpnTrigger = rootState.toggleVpnTrigger,
                            openRouteTarget = rootState.openRouteTarget,
                            onConsumePrefilledUrl = viewModel::consumePrefilledSubUrl,
                            onConsumeToggleVpn = viewModel::consumeToggleVpnTrigger,
                            onConsumeOpenRoute = viewModel::consumeOpenRouteTarget,
                            onCompleteOnboarding = viewModel::completeOnboarding
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_TOGGLE_VPN = "extra_toggle_vpn"
        const val EXTRA_OPEN_ROUTE = "extra_open_route"
    }
}

@Composable
private fun AppLockScreen(
    expectedPin: String,
    onUnlocked: () -> Unit
) {
    val colors = PasarGuardTheme.colors
    var entered by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = colors.heroEnd,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.app_lock_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.app_lock_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = entered,
                        onValueChange = {
                            if (it.length <= 8 && it.all(Char::isDigit)) {
                                entered = it
                                wrongPin = false
                                if (it == expectedPin) onUnlocked()
                            }
                        },
                        singleLine = true,
                        isError = wrongPin,
                        label = { Text(stringResource(R.string.app_lock_pin_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (wrongPin) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.app_lock_wrong_pin),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.statusDanger
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    GradientButton(
                        text = stringResource(R.string.app_lock_unlock),
                        onClick = {
                            if (entered == expectedPin) {
                                onUnlocked()
                            } else {
                                wrongPin = true
                                entered = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
