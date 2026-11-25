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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(val repository: SupportRepository) : ViewModel()

@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onAccount: () -> Unit,
    diagnostic: SupportDiagnostic? = null,
    suggestionOrder: String? = null,
    initialThread: String? = null,
    viewModel: SupportViewModel = hiltViewModel()
) {
    val repository = viewModel.repository
    var thread by rememberSaveable { mutableStateOf(initialThread) }
    var lastRead by remember(thread) { mutableLongStateOf(0) }
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
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("") }
    var orders by remember { mutableStateOf(emptyList<SupportOrder>()) }
    var orderMenu by remember { mutableStateOf(false) }
    var orderLoading by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var replyDraftAvailable by remember { mutableStateOf(false) }
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

    val accountVersion by repository.accountChanges.collectAsStateWithLifecycle()
    var observedAccountVersion by remember { mutableStateOf(accountVersion) }
    LaunchedEffect(accountVersion) {
        if (observedAccountVersion != accountVersion) {
            observedAccountVersion = accountVersion
            thread = null
            creating = false
            recent = null
            summaries = emptyList()
            older = emptyList()
            body = ""
            subject = ""
            order = ""
            denied = false
            failed = false
            loaded = false
        }
    }

    val draftScope = if (creating) "new" else thread
    LaunchedEffect(draftScope, accountVersion) {
        val saved = draftScope?.let(repository::loadDraft)
        replyDraftAvailable = saved?.body?.isNotBlank() == true
        if (saved != null) {
            body = saved.body
            key = saved.requestId
            if (creating) {
                subject = saved.subject
                order = suggestionOrder ?: saved.orderId
                category = if (suggestionOrder != null) "payment" else saved.category
                attach = diagnostic != null && saved.attach
            }
        }
    }

    fun storeDraft() {
        val id = draftScope ?: return
        val text = SupportDraft(body, key, if (creating) subject else "",
            if (creating) order else "", category, attach)
        repository.saveDraft(id, text.takeIf { it.body.isNotBlank() || it.subject.isNotBlank() })
    }

    LaunchedEffect(query, statusFilter) {
        summaries = emptyList()
        hasMoreThreads = false
        loaded = false
    }

    LaunchedEffect(thread, creating, refresh, life, query, statusFilter, accountVersion) {
        if (!repository.configured || creating || !repository.signedIn()) return@LaunchedEffect
        life.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!busy) attempt {
                    val selected = thread
                    if (selected == null) {
                        if (query.isNotEmpty() && !loaded) delay(250)
                        val first = repository.threads(query = query, status = statusFilter)
                        summaries = (first + summaries.drop(50)).distinctBy { it.id }
                        if (summaries.size <= 50) hasMoreThreads = first.size == 50
                    } else {
                        val result = repository.detail(selected)
                        if (older.isEmpty()) hasMoreMessages = result.messages.size == 50
                        recent = result
                        val last = result.messages.lastOrNull()?.sequence
                        if (last != null && last > lastRead) {
                            repository.read(selected, last)
                            lastRead = last
                        }
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
                        Button(onClick = {
                            resetComposer()
                            subject = ""
                            order = ""
                            category = "connection"
                            attach = false
                            creating = true
                        }) { Text(stringResource(if (repository.loadDraft("new") != null) R.string.support_resume_draft else R.string.support_new)) }
                        OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.support_search)) }, singleLine = true)
                        var filters by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { filters = true }) {
                                Text(stringResource(if (statusFilter.isEmpty()) R.string.support_all_statuses else statusLabel(statusFilter)))
                            }
                            DropdownMenu(filters, { filters = false }) {
                                listOf("", "waiting", "investigating", "resolved").forEach { status ->
                                    DropdownMenuItem(text = {
                                        Text(stringResource(if (status.isEmpty()) R.string.support_all_statuses else statusLabel(status)))
                                    }, onClick = { statusFilter = status; filters = false })
                                }
                            }
                        }
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
                                    val next = repository.threads(summaries.size, query, statusFilter)
                                    summaries = (summaries + next).distinctBy { it.id }
                                    hasMoreThreads = next.size == 50
                                } } finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.support_older)) }
                    }
                } else {
                    if (creating) item {
                        OutlinedTextField(subject, { subject = it.take(120); storeDraft() }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.support_subject)) }, enabled = !busy)
                        var expanded by remember { mutableStateOf(false) }
                        val categories = mapOf("connection" to R.string.support_network, "payment" to R.string.support_payment,
                            "subscription" to R.string.support_subscription, "other" to R.string.support_other)
                        Box {
                            TextButton(onClick = { expanded = true }) { Text(stringResource(categories.getValue(category))) }
                            DropdownMenu(expanded, { expanded = false }) {
                                categories.forEach { (key, label) ->
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                        category = key; expanded = false; storeDraft()
                                    })
                                }
                            }
                        }
                        if (order.isNotBlank()) {
                            Text(stringResource(R.string.support_linked_order, order.takeLast(8)))
                            TextButton(onClick = { order = ""; storeDraft() }, enabled = !busy) {
                                Text(stringResource(R.string.support_remove_order))
                            }
                        }
                        Box {
                            TextButton(enabled = !busy && !orderLoading, onClick = {
                                orderLoading = true
                                scope.launch {
                                    try { attempt { orders = repository.orders(); orderMenu = true } }
                                    finally { orderLoading = false }
                                }
                            }) { Text(stringResource(R.string.support_choose_order)) }
                            DropdownMenu(orderMenu, { orderMenu = false }) {
                                if (orders.isEmpty()) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.support_no_orders)) }, onClick = { orderMenu = false })
                                orders.forEach { placed ->
                                    DropdownMenuItem(text = { Text(stringResource(R.string.support_order_choice,
                                        placed.id.takeLast(8), placed.final_price_toman)) }, onClick = {
                                        order = placed.id
                                        category = "payment"
                                        orderMenu = false
                                        storeDraft()
                                    })
                                }
                            }
                        }
                        diagnostic?.let {
                            Row {
                                Checkbox(checked = attach, onCheckedChange = { value -> attach = value; storeDraft() }, enabled = !busy)
                                Text(stringResource(R.string.support_attach_preview))
                            }
                            if (attach) Text(stringResource(R.string.support_report, stringResource(networkLabel(it.network)),
                                stringResource(failureLabel(it.failure)), it.version))
                        }
                    }
                    val messages = (older + recent?.messages.orEmpty()).distinctBy { it.sequence }
                    if (!creating) item {
                        recent?.let { current ->
                            Text(stringResource(statusLabel(current.status)))
                            current.order_id?.let { Text(stringResource(R.string.support_linked_order, it.takeLast(8))) }
                            TextButton(enabled = !busy, onClick = {
                                if (current.status != "resolved") confirmClose = true
                                else {
                                    busy = true
                                    scope.launch {
                                        try { attempt { repository.changeStatus(current.id, "waiting"); refresh++ } }
                                        finally { busy = false }
                                    }
                                }
                            }) { Text(stringResource(if (current.status == "resolved") R.string.support_reopen else R.string.support_close)) }
                            if (replyDraftAvailable) Text(stringResource(R.string.support_draft_saved))
                        }
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
                            if (message.created > 0) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(message.created)), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item {
                        if (!creating && recent?.status == "resolved") return@item
                        OutlinedTextField(body, { body = it.take(4000); storeDraft() }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.support_message)) }, enabled = !busy, minLines = 3)
                        val orderValid = order.isBlank() || order.matches(Regex("[a-f0-9]{32}"))
                        Button(enabled = !busy && body.isNotBlank() && if (creating) subject.isNotBlank() && orderValid else recent != null,
                            onClick = {
                                busy = true
                                scope.launch {
                                    try { attempt {
                                        if (creating) {
                                            val created = repository.create(SupportRequest(key, subject.trim(), body.trim(), category,
                                                order.ifBlank { null }, diagnostic.takeIf { attach }))
                                            repository.saveDraft("new", null)
                                            thread = created
                                            creating = false
                                            recent = null
                                        } else {
                                            val selected = checkNotNull(thread)
                                            repository.reply(selected, key, body.trim())
                                            repository.saveDraft(selected, null)
                                        }
                                        replyDraftAvailable = false
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
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text(stringResource(R.string.support_close)) },
        text = { Text(stringResource(R.string.support_close_body)) },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = {
            confirmClose = false
            val selected = thread ?: return@TextButton
            busy = true
            scope.launch {
                try { attempt { repository.changeStatus(selected, "resolved"); refresh++ } }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.support_close)) } }
    )

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
