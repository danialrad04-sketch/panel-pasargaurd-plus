package com.pasarguard.vpn.ui.screens.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.InstalledAppInfo
import com.pasarguard.vpn.domain.model.SplitTunnelMode
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SplitTunnelUiState(
    val mode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackages: Set<String> = emptySet(),
    val apps: List<InstalledAppInfo> = emptyList(),
    val query: String = ""
)

@HiltViewModel
class SplitTunnelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val query = MutableStateFlow("")

    val uiState: StateFlow<SplitTunnelUiState> = combine(
        settingsRepository.settings,
        installedApps,
        query
    ) { settings, apps, q ->
        val filtered = if (q.isBlank()) apps else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
        SplitTunnelUiState(
            mode = settings.splitTunnelMode,
            selectedPackages = settings.splitTunnelPackages,
            apps = filtered,
            query = q
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SplitTunnelUiState())

    init {
        viewModelScope.launch {
            installedApps.value = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val selfPkg = context.packageName
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != selfPkg && pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { app ->
                        InstalledAppInfo(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    fun onQueryChange(value: String) { query.value = value }

    fun setMode(mode: SplitTunnelMode) {
        viewModelScope.launch { settingsRepository.setSplitTunnelMode(mode) }
    }

    fun togglePackage(pkg: String) {
        viewModelScope.launch {
            val current = settingsRepository.getSnapshot().splitTunnelPackages.toMutableSet()
            if (!current.add(pkg)) current.remove(pkg)
            settingsRepository.setSplitTunnelPackages(current)
        }
    }

    /**
     * 1-tap preset that switches to BYPASS_SELECTED and selects installed Iranian banking,
     * payment, and local service apps automatically.
     */
    fun applyIranianBankingPreset() {
        viewModelScope.launch {
            val keywords = listOf(
                "bank", "blu", "melli", "mellat", "saderat", "tejarat", "sepah",
                "saman", "pasargad", "parsian", "keshavarzi", "refah", "resalat",
                "ayandeh", "shahr", "asanpardakht", "ap.ir", "shaparak", "snapp",
                "digikala", "divar", "bazaar", "myket", "rubika", "eitaa", "bale"
            )
            val matched = installedApps.value
                .filter { app ->
                    val pkg = app.packageName.lowercase()
                    val label = app.label.lowercase()
                    pkg.startsWith("ir.") || keywords.any { kw -> pkg.contains(kw) || label.contains(kw) }
                }
                .map { it.packageName }
                .toSet()

            val updated = settingsRepository.getSnapshot().splitTunnelPackages + matched
            settingsRepository.setSplitTunnelMode(SplitTunnelMode.BYPASS_SELECTED)
            settingsRepository.setSplitTunnelPackages(updated)
        }
    }
}

@Composable
fun SplitTunnelScreen(
    onBack: () -> Unit,
    viewModel: SplitTunnelViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current

    DeepSpaceBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = colors.textPrimary
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.split_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.split_selected_count, state.selectedPackages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                listOf(
                    SplitTunnelMode.DISABLED to R.string.split_mode_disabled,
                    SplitTunnelMode.BYPASS_SELECTED to R.string.split_mode_bypass,
                    SplitTunnelMode.ONLY_SELECTED to R.string.split_mode_only
                ).forEach { (mode, res) ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(stringResource(res)) }
                    )
                }
            }

            AssistChip(
                onClick = viewModel::applyIranianBankingPreset,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AccountBalance,
                        contentDescription = null,
                        tint = colors.heroEnd,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = { Text(stringResource(R.string.split_preset_iran_banks)) }
            )

            Spacer(Modifier.height(Spacing.xs))

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.split_search_apps)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(items = state.apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in state.selectedPackages
                    val iconBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                context.packageManager.getApplicationIcon(app.packageName)
                                    .toBitmap(96, 96)
                                    .asImageBitmap()
                            }.getOrNull()
                        }
                    }

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        onClick = { viewModel.togglePackage(app.packageName) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap!!,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Spacer(Modifier.width(Spacing.sm))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.togglePackage(app.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }
}
