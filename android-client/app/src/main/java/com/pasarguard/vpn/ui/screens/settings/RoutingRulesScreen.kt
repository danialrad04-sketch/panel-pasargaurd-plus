package com.pasarguard.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.CustomRoutingRule
import com.pasarguard.vpn.domain.model.RoutingMode
import com.pasarguard.vpn.domain.model.RoutingRuleAction
import com.pasarguard.vpn.domain.model.RoutingRuleMatchType
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.components.GradientButton
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RoutingRulesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch { settingsRepository.setRoutingMode(mode) }
    }

    fun addRule(matchType: RoutingRuleMatchType, value: String, action: RoutingRuleAction) {
        val clean = value.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val current = settingsRepository.getSnapshot().customRoutingRules
            val updated = current + CustomRoutingRule(
                id = UUID.randomUUID().toString(),
                matchType = matchType,
                value = clean,
                action = action
            )
            settingsRepository.setCustomRoutingRules(updated)
            settingsRepository.setRoutingMode(RoutingMode.CUSTOM)
        }
    }

    fun removeRule(ruleId: String) {
        viewModelScope.launch {
            val updated = settingsRepository.getSnapshot().customRoutingRules.filterNot { it.id == ruleId }
            settingsRepository.setCustomRoutingRules(updated)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingRulesScreen(
    onBack: () -> Unit,
    viewModel: RoutingRulesViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors

    var matchType by remember { mutableStateOf(RoutingRuleMatchType.DOMAIN) }
    var action by remember { mutableStateOf(RoutingRuleAction.DIRECT) }
    var ruleValue by remember { mutableStateOf("") }

    DeepSpaceBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md),
            contentPadding = PaddingValues(bottom = Spacing.xl),
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
                        text = stringResource(R.string.routing_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                    Text(
                        text = stringResource(R.string.routing_mode_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(
                            RoutingMode.GLOBAL to R.string.routing_global,
                            RoutingMode.BYPASS_LAN to R.string.routing_bypass_lan,
                            RoutingMode.BYPASS_IRAN to R.string.routing_bypass_iran,
                            RoutingMode.CUSTOM to R.string.routing_custom
                        ).forEach { (mode, resId) ->
                            FilterChip(
                                selected = settings.routingMode == mode,
                                onClick = { viewModel.setRoutingMode(mode) },
                                label = { Text(stringResource(resId)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.routing_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                    Text(
                        text = stringResource(R.string.routing_add_rule),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Spacing.sm))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        RoutingRuleMatchType.entries.forEach { t ->
                            FilterChip(
                                selected = matchType == t,
                                onClick = { matchType = t },
                                label = { Text(t.name) }
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.sm))

                    OutlinedTextField(
                        value = ruleValue,
                        onValueChange = { ruleValue = it },
                        placeholder = {
                            Text(
                                when (matchType) {
                                    RoutingRuleMatchType.DOMAIN -> "example.ir"
                                    RoutingRuleMatchType.IP -> "192.168.1.0/24"
                                    RoutingRuleMatchType.GEOSITE -> "category-ir"
                                    RoutingRuleMatchType.GEOIP -> "ir"
                                }
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(Spacing.sm))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(
                            RoutingRuleAction.DIRECT to R.string.routing_action_direct,
                            RoutingRuleAction.PROXY to R.string.routing_action_proxy,
                            RoutingRuleAction.BLOCK to R.string.routing_action_block
                        ).forEach { (act, resId) ->
                            FilterChip(
                                selected = action == act,
                                onClick = { action = act },
                                label = { Text(stringResource(resId)) }
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))

                    GradientButton(
                        text = stringResource(R.string.routing_add_rule),
                        leadingIcon = Icons.Rounded.Add,
                        enabled = ruleValue.isNotBlank(),
                        onClick = {
                            viewModel.addRule(matchType, ruleValue, action)
                            ruleValue = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            items(items = settings.customRoutingRules, key = { it.id }) { rule ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rule.matchType.name}: ${rule.value}",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                text = rule.action.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (rule.action) {
                                    RoutingRuleAction.DIRECT -> colors.statusConnected
                                    RoutingRuleAction.PROXY -> colors.heroEnd
                                    RoutingRuleAction.BLOCK -> colors.statusDanger
                                }
                            )
                        }
                        IconButton(onClick = { viewModel.removeRule(rule.id) }) {
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
