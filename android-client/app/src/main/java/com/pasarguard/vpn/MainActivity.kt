package com.pasarguard.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.ThemeMode
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.ui.navigation.AppNavHost
import com.pasarguard.vpn.ui.navigation.Routes
import com.pasarguard.vpn.ui.theme.PasarGuardVpnTheme
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
    val language: AppLanguage = AppLanguage.PERSIAN,
    val prefilledSubUrl: String? = null,
    val paymentCallbackTimestamp: Long = 0L
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

            _state.update {
                it.copy(
                    isReady = true,
                    startDestination = start,
                    themeMode = initialSettings.themeMode,
                    language = initialSettings.language
                )
            }

            settingsRepository.settings.collect { updated ->
                _state.update {
                    it.copy(
                        themeMode = updated.themeMode,
                        language = updated.language
                    )
                }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }

    fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
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
            if (rootState.isReady) {
                PasarGuardVpnTheme(
                    themeMode = rootState.themeMode,
                    language = rootState.language
                ) {
                    AppNavHost(
                        startDestination = rootState.startDestination,
                        prefilledSubscriptionUrl = rootState.prefilledSubUrl,
                        paymentCallbackTimestamp = rootState.paymentCallbackTimestamp,
                        onConsumePrefilledUrl = viewModel::consumePrefilledSubUrl,
                        onCompleteOnboarding = viewModel::completeOnboarding
                    )
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
}
