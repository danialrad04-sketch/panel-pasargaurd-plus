package com.pasarguard.vpn.ui.screens.renewal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.RenewalPlan
import com.pasarguard.vpn.domain.repository.PaymentRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.usecase.PollRenewalStatusUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RenewalStage {
    SELECT_PLAN,
    POLLING_PAYMENT,
    RENEWED_SUCCESS
}

data class RenewalUiState(
    val plans: List<RenewalPlan> = emptyList(),
    val selectedPlanId: String? = null,
    val language: AppLanguage = AppLanguage.PERSIAN,
    val stage: RenewalStage = RenewalStage.SELECT_PLAN,
    val isLoadingPlans: Boolean = true,
    val isCreatingCheckout: Boolean = false,
    val pendingPaymentUrl: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class RenewalViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val pollRenewalStatus: PollRenewalStatusUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RenewalUiState())
    val uiState: StateFlow<RenewalUiState> = _uiState.asStateFlow()

    private var preCheckoutExpire: Long? = null
    private var preCheckoutLimit: Long? = null

    init {
        viewModelScope.launch {
            val lang = settingsRepository.getSnapshot().language
            _uiState.update { it.copy(language = lang, isLoadingPlans = true) }
            paymentRepository.getAvailablePlans()
                .onSuccess { plans ->
                    val defaultPlan = plans.firstOrNull { it.isPopular } ?: plans.firstOrNull()
                    _uiState.update {
                        it.copy(
                            plans = plans,
                            selectedPlanId = defaultPlan?.id,
                            isLoadingPlans = false
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoadingPlans = false, errorMessage = err.localizedMessage)
                    }
                }
        }
    }

    fun selectPlan(planId: String) {
        _uiState.update { it.copy(selectedPlanId = planId, errorMessage = null) }
    }

    fun startCheckout() {
        val planId = _uiState.value.selectedPlanId ?: return
        val token = subscriptionRepository.getSavedCredentials()?.token ?: return

        viewModelScope.launch {
            val currentSub = subscriptionRepository.observeSubscription().firstOrNull()
            preCheckoutExpire = currentSub?.expireEpochSeconds
            preCheckoutLimit = currentSub?.dataLimitBytes

            _uiState.update { it.copy(isCreatingCheckout = true, errorMessage = null) }
            paymentRepository.createCheckoutSession(
                planId = planId,
                subscriptionToken = token,
                callbackUrl = BrandConfig.paymentCallbackUri
            ).onSuccess { session ->
                _uiState.update {
                    it.copy(
                        isCreatingCheckout = false,
                        pendingPaymentUrl = session.paymentUrl,
                        stage = RenewalStage.POLLING_PAYMENT
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCreatingCheckout = false,
                        errorMessage = err.localizedMessage
                    )
                }
            }
        }
    }

    fun consumePendingPaymentUrl() {
        _uiState.update { it.copy(pendingPaymentUrl = null) }
    }

    fun pollAfterCallback() {
        viewModelScope.launch {
            _uiState.update { it.copy(stage = RenewalStage.POLLING_PAYMENT, errorMessage = null) }
            val renewed = pollRenewalStatus(
                previousExpire = preCheckoutExpire,
                previousDataLimit = preCheckoutLimit
            ).getOrDefault(false)

            if (renewed) {
                _uiState.update { it.copy(stage = RenewalStage.RENEWED_SUCCESS) }
            }
        }
    }
}

@Composable
fun RenewalScreen(
    paymentCallbackTrigger: Long,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: RenewalViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    val isPersian = state.language == AppLanguage.PERSIAN

    LaunchedEffect(state.pendingPaymentUrl) {
        val url = state.pendingPaymentUrl
        if (!url.isNullOrBlank()) {
            viewModel.consumePendingPaymentUrl()
            Formatters.launchCustomTab(context, url)
        }
    }

    LaunchedEffect(paymentCallbackTrigger) {
        if (paymentCallbackTrigger > 0L) {
            viewModel.pollAfterCallback()
        }
    }

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = colors.textPrimary
                    )
                }
                Text(
                    text = stringResource(R.string.renew_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
            }

            when (state.stage) {
                RenewalStage.SELECT_PLAN -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Text(
                            text = stringResource(R.string.renew_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )

                        if (state.isLoadingPlans) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = colors.heroEnd)
                            }
                        } else {
                            state.plans.forEach { plan ->
                                val selected = plan.id == state.selectedPlanId
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (selected) Modifier.border(
                                                2.dp,
                                                colors.heroEnd,
                                                RoundedCornerShape(24.dp)
                                            ) else Modifier
                                        ),
                                    cornerRadius = 24.dp,
                                    onClick = { viewModel.selectPlan(plan.id) }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            if (plan.isPopular) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(colors.heroEnd.copy(alpha = 0.2f))
                                                        .padding(horizontal = 10.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.renew_popular),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.heroEnd
                                                    )
                                                }
                                                Spacer(Modifier.height(6.dp))
                                            }
                                            Text(
                                                text = if (isPersian) plan.titleFa else plan.titleEn,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = colors.textPrimary
                                            )
                                            val badge = if (isPersian) plan.badgeFa else plan.badgeEn
                                            if (!badge.isNullOrBlank()) {
                                                Text(
                                                    text = badge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.statusConnected
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (isPersian) plan.priceDisplayFa else plan.priceDisplayEn,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = colors.heroEnd
                                        )
                                    }
                                }
                            }
                        }

                        if (state.errorMessage != null) {
                            Text(
                                text = stringResource(R.string.renew_error_checkout),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.statusDanger
                            )
                        }

                        Spacer(Modifier.height(Spacing.sm))

                        GradientButton(
                            text = stringResource(R.string.renew_pay_cta),
                            onClick = { viewModel.startCheckout() },
                            enabled = state.selectedPlanId != null,
                            isLoading = state.isCreatingCheckout,
                            leadingIcon = Icons.Rounded.OpenInBrowser,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                RenewalStage.POLLING_PAYMENT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            color = colors.heroEnd,
                            strokeWidth = 4.dp
                        )
                        Spacer(Modifier.height(Spacing.lg))
                        Text(
                            text = stringResource(R.string.renew_polling_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.renew_polling_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.xl))
                        OutlinedButton(onClick = { viewModel.pollAfterCallback() }) {
                            Text(stringResource(R.string.renew_check_again))
                        }
                    }
                }

                RenewalStage.RENEWED_SUCCESS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = colors.statusConnected,
                            modifier = Modifier.size(76.dp)
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.renew_success_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.renew_success_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.xl))
                        GradientButton(
                            text = stringResource(R.string.renew_done),
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
