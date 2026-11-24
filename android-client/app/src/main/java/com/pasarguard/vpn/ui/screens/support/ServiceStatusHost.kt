package com.pasarguard.vpn.ui.screens.support

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.repository.ServiceStatus
import com.pasarguard.vpn.data.repository.SupportRepository
import com.pasarguard.vpn.domain.repository.VpnController
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.worker.ServiceMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ServiceStatusViewModel @Inject constructor(
    val repository: SupportRepository,
    controller: VpnController
) : ViewModel() {
    val serverIds = controller.sessionState.map { it.activeServer?.id }.distinctUntilChanged()
}

private val LocalServiceStatus = staticCompositionLocalOf { ServiceStatus() }
private val LocalOpenStatus = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun ServiceStatusHost(
    onOpenStatus: () -> Unit,
    viewModel: ServiceStatusViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val repository = viewModel.repository
    var status by remember { mutableStateOf(ServiceStatus()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    LaunchedEffect(lifecycle, repository) {
        if (!repository.configured) return@LaunchedEffect
        delay(1_200)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    status = repository.status()
                    delay(60_000)
                }
            }
            launch {
                viewModel.serverIds.collect { server -> ServiceMessaging.refreshRegistration(context, server) }
            }
        }
    }
    CompositionLocalProvider(LocalServiceStatus provides status, LocalOpenStatus provides onOpenStatus, content = content)
}

@Composable
fun ServiceIncidentBanner(scope: String, serverId: String? = null) {
    val status = LocalServiceStatus.current
    val incident = status.items.firstOrNull {
        it.scope == scope && it.status == "investigating" && (it.server_ids.isEmpty() || serverId in it.server_ids)
    } ?: return
    GlassCard(Modifier.fillMaxWidth(), onClick = LocalOpenStatus.current) {
        Text(incident.title, style = MaterialTheme.typography.titleMedium)
        if (incident.guidance.isNotBlank()) Text(incident.guidance)
        if (status.stale) Text(statusAge(status), style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.service_status), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ServiceStatusScreen(onBack: () -> Unit) {
    val status = LocalServiceStatus.current
    DeepSpaceBackground {
        LazyColumn(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(stringResource(R.string.service_status), style = MaterialTheme.typography.headlineMedium)
                if (status.stale || status.checkedAt == 0L) Text(statusAge(status))
                if (!status.stale && status.checkedAt > 0 && status.items.none { it.status == "investigating" }) {
                    Text(stringResource(R.string.service_no_active))
                }
            }
            items(status.items, key = { it.id }) { incident ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(incident.title, style = MaterialTheme.typography.titleMedium)
                    val scopeLabel = when (incident.scope) {
                        "payment" -> R.string.support_payment
                        "update" -> R.string.settings_check_updates
                        else -> R.string.support_network
                    }
                    Text(stringResource(scopeLabel) + " · " + stringResource(
                        if (incident.status == "resolved") R.string.support_resolved else R.string.support_investigating
                    ), style = MaterialTheme.typography.labelMedium)
                    Text(incident.body)
                    if (incident.guidance.isNotBlank()) Text(incident.guidance)
                }
            }
        }
    }
}

@Composable
private fun statusAge(status: ServiceStatus): String {
    if (status.checkedAt == 0L) return stringResource(R.string.service_unavailable)
    return stringResource(R.string.service_last_check, DateFormat.getDateTimeInstance().format(Date(status.checkedAt)))
}
