package com.pasarguard.vpn.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.diagnostics.DiagnosticsEngine
import com.pasarguard.vpn.data.diagnostics.SpeedTestProgress
import com.pasarguard.vpn.domain.model.DiagnosticsIpInfo
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.ui.components.AnimatedSpeedGauge
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val isLoadingIp: Boolean = false,
    val ipInfo: DiagnosticsIpInfo? = null,
    val ipError: String? = null,
    val speedTest: SpeedTestProgress = SpeedTestProgress(
        isRunning = false,
        currentMbps = 0.0,
        peakMbps = 0.0,
        averageMbps = 0.0,
        downloadedBytes = 0L,
        progressFraction = 0f
    ),
    val ipInfoEndpoint: String = DiagnosticsEngine.DEFAULT_IP_ENDPOINT,
    val speedTestUrl: String = DiagnosticsEngine.DEFAULT_SPEED_URL
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsEngine: DiagnosticsEngine,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private var speedJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.getSnapshot()
            _uiState.update {
                it.copy(
                    ipInfoEndpoint = settings.ipInfoEndpoint,
                    speedTestUrl = settings.speedTestUrl
                )
            }
            refreshIpInfo()
        }
    }

    fun refreshIpInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingIp = true, ipError = null) }
            val result = diagnosticsEngine.fetchIpAndDnsInfo(_uiState.value.ipInfoEndpoint)
            result.onSuccess { info ->
                _uiState.update { it.copy(isLoadingIp = false, ipInfo = info, ipError = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingIp = false,
                        ipError = err.localizedMessage ?: "Failed to check IP"
                    )
                }
            }
        }
    }

    fun toggleSpeedTest() {
        if (_uiState.value.speedTest.isRunning) {
            speedJob?.cancel()
            speedJob = null
            _uiState.update {
                it.copy(speedTest = it.speedTest.copy(isRunning = false))
            }
            return
        }
        speedJob = viewModelScope.launch {
            diagnosticsEngine.runDownloadSpeedTest(_uiState.value.speedTestUrl).collect { progress ->
                _uiState.update { it.copy(speedTest = progress) }
            }
        }
    }

    fun saveEndpoints(ipEndpoint: String, speedUrl: String) {
        viewModelScope.launch {
            settingsRepository.setIpInfoEndpoint(ipEndpoint)
            settingsRepository.setSpeedTestUrl(speedUrl)
            _uiState.update {
                it.copy(
                    ipInfoEndpoint = ipEndpoint.trim().ifBlank { DiagnosticsEngine.DEFAULT_IP_ENDPOINT },
                    speedTestUrl = speedUrl.trim().ifBlank { DiagnosticsEngine.DEFAULT_SPEED_URL }
                )
            }
        }
    }

    override fun onCleared() {
        speedJob?.cancel()
        super.onCleared()
    }
}

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current

    var showEndpoints by remember { mutableStateOf(false) }
    var ipInput by remember(state.ipInfoEndpoint) { mutableStateOf(state.ipInfoEndpoint) }
    var speedInput by remember(state.speedTestUrl) { mutableStateOf(state.speedTestUrl) }

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = colors.textPrimary
                        )
                    }
                    Text(
                        text = stringResource(R.string.diagnostics_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                }
                IconButton(onClick = viewModel::refreshIpInfo) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.servers_refresh),
                        tint = colors.heroEnd
                    )
                }
            }

            // Exit IP & DNS Leak Check Card
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.heroEnd)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.diagnostics_exit_ip),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                when {
                    state.isLoadingIp -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = colors.heroEnd,
                                strokeWidth = 2.5.dp
                            )
                        }
                    }

                    state.ipError != null -> {
                        Text(
                            text = state.ipError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.statusDanger
                        )
                    }

                    state.ipInfo != null -> {
                        val info = state.ipInfo!!
                        DiagRow(stringResource(R.string.diagnostics_ip_label), info.ip)
                        DiagRow(stringResource(R.string.diagnostics_location_label), "${info.country}, ${info.city}")
                        DiagRow(stringResource(R.string.diagnostics_isp_label), info.isp)

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = Spacing.sm),
                            color = colors.glassBorderTop
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (info.dnsLeakDetected) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = if (info.dnsLeakDetected) colors.statusWarning else colors.statusConnected,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Column {
                                Text(
                                    text = stringResource(R.string.diagnostics_dns_check),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = info.dnsResolverNote,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Live Through-Tunnel Download Speed Test Gauge
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.heroEnd)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.diagnostics_speed_test),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    AnimatedSpeedGauge(
                        currentMbps = state.speedTest.currentMbps,
                        isRunning = state.speedTest.isRunning,
                        label = stringResource(
                            if (state.speedTest.isRunning) R.string.diagnostics_testing
                            else R.string.diagnostics_download_speed
                        )
                    )

                    if (state.speedTest.isRunning) {
                        Spacer(Modifier.height(Spacing.sm))
                        LinearProgressIndicator(
                            progress = { state.speedTest.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.heroEnd
                        )
                    }

                    Spacer(Modifier.height(Spacing.md))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SpeedMetricCell(
                            label = stringResource(R.string.diagnostics_peak),
                            value = Formatters.formatMbps(state.speedTest.peakMbps, persianDigits)
                        )
                        SpeedMetricCell(
                            label = stringResource(R.string.diagnostics_avg),
                            value = Formatters.formatMbps(state.speedTest.averageMbps, persianDigits)
                        )
                        SpeedMetricCell(
                            label = stringResource(R.string.diagnostics_transferred),
                            value = Formatters.formatBytes(state.speedTest.downloadedBytes, persianDigits)
                        )
                    }

                    if (state.speedTest.errorMessage != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = state.speedTest.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.statusDanger
                        )
                    }

                    Spacer(Modifier.height(Spacing.md))

                    GradientButton(
                        text = stringResource(
                            if (state.speedTest.isRunning) R.string.diagnostics_stop_speed
                            else R.string.diagnostics_start_speed
                        ),
                        leadingIcon = if (state.speedTest.isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        onClick = viewModel::toggleSpeedTest,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Configurable Endpoints
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                onClick = { showEndpoints = !showEndpoints }
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_custom_endpoints),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                if (showEndpoints) {
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text(stringResource(R.string.diagnostics_ip_endpoint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = speedInput,
                        onValueChange = { speedInput = it },
                        label = { Text(stringResource(R.string.diagnostics_speed_endpoint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { viewModel.saveEndpoints(ipInput, speedInput) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    val colors = PasarGuardTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary
        )
    }
}

@Composable
private fun SpeedMetricCell(label: String, value: String) {
    val colors = PasarGuardTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}
