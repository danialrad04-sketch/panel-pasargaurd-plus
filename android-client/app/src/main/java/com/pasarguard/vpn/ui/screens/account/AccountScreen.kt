package com.pasarguard.vpn.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import com.pasarguard.vpn.ui.components.PasarDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.api.CustomerApi
import com.pasarguard.vpn.data.api.CustomerRequestException
import com.pasarguard.vpn.data.api.CancelQuote
import com.pasarguard.vpn.data.api.CustomerWallet
import com.pasarguard.vpn.data.api.OrderDto
import com.pasarguard.vpn.domain.usecase.ValidateAndLoginUseCase
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.updates.LocalAutoDownloadSetting
import com.pasarguard.vpn.ui.updates.LocalCheckForUpdates
import com.pasarguard.vpn.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val api: CustomerApi,
    private val validate: ValidateAndLoginUseCase
) : ViewModel() {
    var email by mutableStateOf<String?>(null)
        private set
    var wallet by mutableStateOf<CustomerWallet?>(null)
        private set
    var orders by mutableStateOf(emptyList<OrderDto>())
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var needsCode by mutableStateOf(false)
        private set

    fun reload() = run { if (api.signedIn) load() }
    fun send(email: String) = run {
        api.sendCode(email)
        needsCode = true
        notice = R.string.account_code_sent
    }
    fun authenticate(email: String, password: String, code: String?, onSuccess: () -> Unit) = run {
        api.login(email, password, code)
        needsCode = false
        onSuccess()
        load()
    }
    fun logout() = run {
        try { api.logout() } finally { clear() }
    }
    fun connect(id: String, done: () -> Unit) = run {
        validate(api.subscription(id), requireServers = true).getOrThrow()
        done()
    }
    suspend fun preview(id: String) = api.cancelPreview(id)

    suspend fun cancel(id: String, quote: String) {
        api.cancel(id, quote)
        reload()
    }

    private suspend fun load() {
        email = api.email()
        wallet = api.wallet()
        orders = api.orders()
    }

    private fun clear() {
        email = null
        orders = emptyList()
        wallet = null
    }

    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!api.signedIn) clear()
                notice = when {
                    error !is CustomerRequestException -> R.string.account_error
                    error.code == "INVALID_VERIFICATION_CODE" -> R.string.account_bad_code
                    error.status == 401 -> R.string.account_bad_login
                    error.status == 429 -> R.string.account_try_later
                    error.code == "CUSTOMER_EMAIL_UNAVAILABLE" -> R.string.account_email_unavailable
                    else -> R.string.account_error
                }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun AccountScreen(
    onBack: () -> Unit, onPlans: () -> Unit, onSettings: () -> Unit, onOrder: (String) -> Unit,
    onConnect: () -> Unit, viewModel: AccountViewModel = hiltViewModel()
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var cancelId by remember { mutableStateOf<String?>(null) }
    val update = LocalCheckForUpdates.current
    val autoDownload = LocalAutoDownloadSetting.current
    val persian = LocalPersianDigits.current

    LaunchedEffect(Unit) { viewModel.reload() }
    DeepSpaceBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    Text(stringResource(R.string.customer_portal), style = MaterialTheme.typography.headlineMedium)
                }
            }
            if (viewModel.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            viewModel.notice?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (viewModel.email == null) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(email, { email = it }, label = { Text("Gmail") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                            if (viewModel.needsCode) {
                                OutlinedTextField(code, { code = it.mapNotNull { digit -> digit.digitToIntOrNull() }.take(6).joinToString("") },
                                    label = { Text(stringResource(R.string.account_code)) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            }
                            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.account_password)) },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                            Button(
                                enabled = !viewModel.busy && email.isNotBlank() && password.isNotBlank() && (!viewModel.needsCode || code.length == 6),
                                onClick = {
                                    viewModel.authenticate(email, password, code.takeIf { viewModel.needsCode }) {
                                        code = ""; password = ""
                                    }
                                }, modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(if (viewModel.needsCode) R.string.account_verify else R.string.account_login)) }
                            OutlinedButton(enabled = !viewModel.busy && email.isNotBlank(), onClick = { viewModel.send(email) },
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_signup_reset)) }
                        }
                    }
                }
            } else {
                item {
                    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                        Text(viewModel.email.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        val balance = viewModel.wallet?.balance
                        Text(
                            balance?.let {
                                stringResource(R.string.account_wallet_amount, Formatters.formatNumber(it, persian))
                            } ?: stringResource(R.string.account_wallet),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Button(onClick = onPlans, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_purchase)) }
                        TextButton(onClick = viewModel::reload, enabled = !viewModel.busy) { Text(stringResource(R.string.account_refresh)) }
                    }
                }
                items(viewModel.orders, key = { it.id }) { order ->
                    val refund = viewModel.wallet?.refunds?.firstOrNull { it.orderId == order.id }
                    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                        Text(if (persian) order.planTitleFa else order.planTitleEn, style = MaterialTheme.typography.titleMedium)
                        when {
                            refund != null -> {
                                Text(stringResource(if (refund.finished == null) R.string.account_cancelling else R.string.account_cancelled))
                                refund.amount?.let {
                                    Text(stringResource(R.string.refund_credited, Formatters.formatNumber(it, persian)))
                                }
                            }
                            order.newSubscription && order.activationStatus == "READY" -> {
                                TextButton(enabled = !viewModel.busy, onClick = { viewModel.connect(order.id, onConnect) }) {
                                    Text(stringResource(R.string.login_connect_cta))
                                }
                                TextButton(enabled = !viewModel.busy, onClick = { cancelId = order.id }) {
                                    Text(stringResource(R.string.account_cancel))
                                }
                            }
                            else -> TextButton(onClick = { onOrder(order.id) }) { Text(stringResource(R.string.orders_title)) }
                        }
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.nav_settings)) }
                    update?.let { TextButton(onClick = it) { Text(stringResource(R.string.settings_check_updates)) } }
                    autoDownload?.let { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.update_auto_download), modifier = Modifier.weight(1f))
                            Switch(checked = option.enabled, onCheckedChange = option.onChange)
                        }
                    }
                    if (viewModel.email != null) {
                        TextButton(onClick = viewModel::logout, enabled = !viewModel.busy) { Text(stringResource(R.string.account_logout)) }
                    }
                }
            }
        }
    }

    val pendingRefund = viewModel.wallet?.refunds?.any { it.finished == null } == true
    LaunchedEffect(pendingRefund) {
        while (pendingRefund) {
            kotlinx.coroutines.delay(8_000)
            viewModel.reload()
        }
    }

    cancelId?.let { id ->
        var quote by remember(id) { mutableStateOf<CancelQuote?>(null) }
        var error by remember(id) { mutableStateOf<Int?>(null) }
        var attempt by remember(id) { mutableStateOf(0) }
        var sending by remember(id) { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        LaunchedEffect(id, attempt) {
            try {
                quote = viewModel.preview(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = when ((failure as? CustomerRequestException)?.code) {
                    "RENEWAL_IN_PROGRESS", "RENEWAL_OWNER_MISMATCH" -> R.string.refund_renewal_conflict
                    "SUBSCRIPTION_EXPIRED" -> R.string.refund_expired
                    "REFUND_IN_PROGRESS" -> R.string.account_cancelling
                    else -> R.string.refund_preview_error
                }
            }
        }
        PasarDialog(onDismissRequest = { if (!sending) cancelId = null },
            title = { Text(stringResource(R.string.account_cancel)) },
            text = {
                Column {
                    Text(stringResource(R.string.account_cancel_confirm))
                    val current = quote
                    when {
                        sending -> Text(stringResource(R.string.account_cancelling))
                        error != null -> Text(stringResource(error ?: R.string.refund_preview_error))
                        current == null -> Text(stringResource(R.string.refund_loading))
                        else -> {
                            Text(stringResource(R.string.refund_estimate, Formatters.formatNumber(current.amount, persian)))
                            Text(stringResource(R.string.account_wallet_amount, Formatters.formatNumber(current.balance, persian)))
                            Text(stringResource(R.string.refund_after, Formatters.formatNumber(current.balance + current.amount, persian)))
                        }
                    }
                }
            },
            confirmButton = {
                val current = quote
                TextButton(enabled = !sending && (current != null || error != null), onClick = {
                    if (current == null) {
                        error = null
                        attempt++
                    } else {
                        sending = true
                        scope.launch {
                            try {
                                viewModel.cancel(id, current.quote)
                                cancelId = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                quote = null
                                error = if ((failure as? CustomerRequestException)?.code == "REFUND_QUOTE_CHANGED")
                                    R.string.refund_changed else R.string.refund_preview_error
                            } finally {
                                sending = false
                            }
                        }
                    }
                }) { Text(stringResource(if (error != null) R.string.refund_refresh else R.string.action_confirm)) }
            },
            dismissButton = { TextButton(enabled = !sending, onClick = { cancelId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
