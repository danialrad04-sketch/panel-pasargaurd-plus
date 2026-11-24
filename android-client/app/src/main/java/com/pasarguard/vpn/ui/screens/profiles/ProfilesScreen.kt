package com.pasarguard.vpn.ui.screens.profiles

import com.pasarguard.vpn.ui.components.PasarSheetHandle
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.SubscriptionProfile
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.usecase.ParseSubscriptionInputUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.Formatters
import com.pasarguard.vpn.worker.SubscriptionSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfilesUiState(
    val profiles: List<SubscriptionProfile> = emptyList(),
    val refreshIntervalHours: Int = 6,
    val isBusy: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val parseInput: ParseSubscriptionInputUseCase
) : ViewModel() {

    private val isBusy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfilesUiState> = combine(
        subscriptionRepository.observeProfiles(),
        settingsRepository.settings,
        isBusy,
        error
    ) { profiles, settings: AppSettings, busy, err ->
        ProfilesUiState(
            profiles = profiles,
            refreshIntervalHours = settings.autoRefreshIntervalHours,
            isBusy = busy,
            error = err
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun addProfile(name: String, rawInput: String, colorHex: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            isBusy.value = true
            error.value = null
            val creds = parseInput(rawInput).getOrElse {
                error.value = it.localizedMessage ?: "Invalid subscription link"
                isBusy.value = false
                return@launch
            }
            subscriptionRepository.addProfile(name.ifBlank { "Profile" }, rawInput.trim(), colorHex)
                .onSuccess {
                    isBusy.value = false
                    onDone()
                }
                .onFailure {
                    error.value = it.localizedMessage ?: "Failed to add profile"
                    isBusy.value = false
                }
        }
    }

    fun switchProfile(profileId: String) {
        viewModelScope.launch {
            isBusy.value = true
            error.value = null
            subscriptionRepository.switchProfile(profileId)
                .onFailure { error.value = it.localizedMessage }
            isBusy.value = false
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            subscriptionRepository.deleteProfile(profileId)
        }
    }

    fun setRefreshInterval(hours: Int) {
        viewModelScope.launch {
            settingsRepository.setAutoRefreshIntervalHours(hours)
            SubscriptionSyncWorker.schedule(context, hours.toLong())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    onBack: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current
    var showAddSheet by remember { mutableStateOf(false) }

    val colorPalette = listOf(0xFF06B6D4L, 0xFF10B981L, 0xFF8B5CF6L, 0xFFF59E0BL, 0xFFF43F5EL)

    DeepSpaceBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.md),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                item {
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = colors.textPrimary
                            )
                        }
                        Text(
                            text = stringResource(R.string.profiles_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary
                        )
                    }
                }

                // Auto-Refresh Interval Card
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, tint = colors.heroEnd)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = stringResource(R.string.profiles_refresh_interval),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            listOf(
                                0 to stringResource(R.string.profiles_interval_manual),
                                1 to Formatters.toPersianDigitsIfNeeded("1h", persianDigits),
                                6 to Formatters.toPersianDigitsIfNeeded("6h", persianDigits),
                                12 to Formatters.toPersianDigitsIfNeeded("12h", persianDigits),
                                24 to Formatters.toPersianDigitsIfNeeded("24h", persianDigits)
                            ).forEach { (hours, label) ->
                                FilterChip(
                                    selected = state.refreshIntervalHours == hours,
                                    onClick = { viewModel.setRefreshInterval(hours) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    item {
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.statusDanger
                        )
                    }
                }

                if (state.profiles.isEmpty()) {
                    item {
                        EmptyStateView(
                            title = stringResource(R.string.profiles_empty_title),
                            description = stringResource(R.string.profiles_empty_desc)
                        )
                    }
                } else {
                    items(items = state.profiles, key = { it.id }) { profile ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (profile.isActive) Modifier.border(
                                        1.5.dp,
                                        colors.heroEnd,
                                        RoundedCornerShape(22.dp)
                                    ) else Modifier
                                ),
                            cornerRadius = 22.dp,
                            onClick = { if (!profile.isActive) viewModel.switchProfile(profile.id) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color(profile.colorHex))
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (profile.lastSyncedEpochMs > 0L) {
                                            stringResource(
                                                R.string.sub_last_synced,
                                                Formatters.formatRelativeSync(profile.lastSyncedEpochMs, persianDigits)
                                            )
                                        } else {
                                            profile.subscriptionUrl.take(36)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (profile.isActive) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.statusConnected
                                    )
                                } else {
                                    IconButton(onClick = { viewModel.deleteProfile(profile.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = colors.statusDanger
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = colors.heroEnd,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(Spacing.lg)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.profiles_add))
            }
        }
    }

    if (showAddSheet) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var selectedColor by remember { mutableLongStateOf(colorPalette.first()) }

        ModalBottomSheet(dragHandle = { PasarSheetHandle() },
            onDismissRequest = { showAddSheet = false },
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
                Text(
                    text = stringResource(R.string.profiles_add),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.login_input_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    colorPalette.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selectedColor == hex) 2.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
                GradientButton(
                    text = stringResource(R.string.action_save),
                    isLoading = state.isBusy,
                    enabled = url.isNotBlank(),
                    onClick = {
                        viewModel.addProfile(name, url, selectedColor) {
                            showAddSheet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
