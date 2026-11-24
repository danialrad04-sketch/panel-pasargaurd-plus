package com.pasarguard.vpn.ui.screens.support

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.repository.*
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(val repository: SupportRepository) : ViewModel()

@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onAccount: () -> Unit,
    diagnostic: SupportDiagnostic? = null,
    suggestionOrder: String? = null,
    viewModel: SupportViewModel = hiltViewModel()
) {
    val repository = viewModel.repository
    var thread by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(diagnostic != null || suggestionOrder != null) }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(suggestionOrder.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(if (suggestionOrder != null) "payment" else "connection") }
    var attach by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var older by remember { mutableStateOf(emptyList<SupportMessage>()) }
    var summaries by remember { mutableStateOf(emptyList<SupportSummary>()) }
    var recent by remember { mutableStateOf<SupportDetail?>(null) }
    var hasMoreThreads by remember { mutableStateOf(false) }
    var hasMoreMessages by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val life = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    suspend fun attempt(action: suspend () -> Unit) {
        try {
            action()
            failed = false
            denied = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            denied = error is SupportHttpException && error.status == 401
            failed = !denied
        }
    }

    LaunchedEffect(thread, creating, refresh, life) {
        if (!repository.configured || creating || !repository.signedIn()) return@LaunchedEffect
        life.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!busy) attempt {
                    val selected = thread
                    if (selected == null) {
                        val first = repository.threads()
                        summaries = (first + summaries.drop(50)).distinctBy { it.id }
                        if (summaries.size <= 50) hasMoreThreads = first.size == 50
                    } else {
                        val result = repository.detail(selected)
                        if (older.isEmpty()) hasMoreMessages = result.messages.size == 50
                        recent = result
                        result.messages.lastOrNull()?.let { repository.read(selected, it.sequence) }
                    }
                    loaded = true
                }
                delay(30_000)
            }
        }
    }

    fun resetComposer() {
        body = ""
        key = UUID.randomUUID().toString()
        failed = false
    }

    DeepSpaceBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                TextButton(onClick = {
                    if (!busy && (thread != null || creating)) {
                        thread = null
                        creating = false
                        recent = null
                        older = emptyList()
                        resetComposer()
                    } else if (!busy) onBack()
                }) { Text(stringResource(R.string.action_back)) }
                Text(recent?.subject ?: stringResource(R.string.support_center), style = MaterialTheme.typography.headlineMedium)
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!repository.configured) {
                item { Text(stringResource(R.string.support_unavailable)) }
            } else if (!repository.signedIn() || denied) {
                item { Button(onClick = onAccount) { Text(stringResource(R.string.support_sign_in)) } }
            } else {
                if (failed) item {
                    Text(stringResource(R.string.support_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.servers_refresh)) }
                }
                if (!creating && thread == null) {
                    item {
                        Button(onClick = { resetComposer(); creating = true }) { Text(stringResource(R.string.support_new)) }
                        if (!loaded && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (loaded && summaries.isEmpty()) Text(stringResource(R.string.support_empty))
                    }
                    items(summaries, key = { it.id }) { summary ->
                        GlassCard(Modifier.fillMaxWidth(), onClick = { resetComposer(); thread = summary.id }) {
                            Text(summary.subject, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(statusLabel(summary.status)))
                            if (summary.unread > 0) Text(stringResource(R.string.support_unread))
                        }
                    }
                    if (hasMoreThreads) item {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try { attempt {
                                    val next = repository.threads(summaries.size)
                                    summaries = (summaries + next).distinctBy { it.id }
                                    hasMoreThreads = next.size == 50
                                } } finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.support_older)) }
                    }
                } else {
                    if (creating) item {
                        OutlinedTextField(subject, { subject = it.take(120) }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.support_subject)) }, enabled = !busy)
                        var expanded by remember { mutableStateOf(false) }
                        val categories = mapOf("connection" to R.string.support_network, "payment" to R.string.support_payment,
                            "subscription" to R.string.support_subscription, "other" to R.string.support_other)
                        Box {
                            TextButton(onClick = { expanded = true }) { Text(stringResource(categories.getValue(category))) }
                            DropdownMenu(expanded, { expanded = false }) {
                                categories.forEach { (key, label) ->
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                        category = key; expanded = false
                                    })
                                }
                            }
                        }
                        OutlinedTextField(order, { order = it.trim().take(32) }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.support_order)) }, singleLine = true, enabled = !busy)
                        diagnostic?.let {
                            Row {
                                Checkbox(checked = attach, onCheckedChange = { value -> attach = value }, enabled = !busy)
                                Text(stringResource(R.string.support_attach_preview))
                            }
                            if (attach) Text(stringResource(R.string.support_report, stringResource(networkLabel(it.network)),
                                stringResource(failureLabel(it.failure)), it.version))
                        }
                    }
                    val messages = (older + recent?.messages.orEmpty()).distinctBy { it.sequence }
                    if (!creating) item {
                        recent?.let { Text(stringResource(statusLabel(it.status))) }
                        if (hasMoreMessages) TextButton(enabled = !busy, onClick = {
                            val cursor = messages.firstOrNull()?.sequence ?: return@TextButton
                            val selected = thread ?: return@TextButton
                            busy = true
                            scope.launch {
                                try { attempt {
                                    val next = repository.detail(selected, cursor).messages
                                    older = next + older
                                    hasMoreMessages = next.size == 50
                                } } finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.support_older)) }
                    }
                    items(messages, key = { it.sequence }) { message ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(stringResource(if (message.sender == "staff") R.string.support_staff else R.string.support_you),
                                style = MaterialTheme.typography.labelMedium)
                            Text(message.body)
                        }
                    }
                    item {
                        OutlinedTextField(body, { body = it.take(4000) }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.support_message)) }, enabled = !busy, minLines = 3)
                        val orderValid = order.isBlank() || order.matches(Regex("[a-f0-9]{32}"))
                        Button(enabled = !busy && body.isNotBlank() && if (creating) subject.isNotBlank() && orderValid else recent != null,
                            onClick = {
                                busy = true
                                scope.launch {
                                    try { attempt {
                                        if (creating) {
                                            thread = repository.create(SupportRequest(key, subject.trim(), body.trim(), category,
                                                order.ifBlank { null }, diagnostic.takeIf { attach }))
                                            creating = false
                                            recent = null
                                        } else {
                                            repository.reply(checkNotNull(thread), key, body.trim())
                                        }
                                        resetComposer()
                                        refresh++
                                    } } finally { busy = false }
                                }
                            }) { Text(stringResource(R.string.support_send)) }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String) = when (status) {
    "resolved" -> R.string.support_resolved
    "investigating" -> R.string.support_investigating
    else -> R.string.support_waiting
}

internal fun networkLabel(network: String) = when (network) {
    "wifi" -> R.string.support_wifi
    "cellular" -> R.string.support_cellular
    "offline" -> R.string.support_offline
    else -> R.string.support_other
}

internal fun failureLabel(failure: String) = when (failure) {
    "network" -> R.string.support_fix_network
    "dns" -> R.string.support_fix_dns
    "subscription" -> R.string.support_fix_subscription
    "server" -> R.string.support_fix_server
    else -> R.string.support_fix_unknown
}
