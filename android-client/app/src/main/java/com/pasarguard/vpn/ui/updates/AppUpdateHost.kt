package com.pasarguard.vpn.ui.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.diagnostics.UpdateChecker
import com.pasarguard.vpn.domain.model.AppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

val LocalCheckForUpdates = staticCompositionLocalOf<(() -> Unit)?> { null }

data class AutoDownloadSetting(val enabled: Boolean, val onChange: (Boolean) -> Unit)
val LocalAutoDownloadSetting = staticCompositionLocalOf<AutoDownloadSetting?> { null }

@Composable
fun AppUpdateHost(checker: UpdateChecker, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var autoDownload by remember { mutableStateOf(checker.autoDownloadEnabled()) }

    fun check(manual: Boolean) {
        if (checking) {
            if (manual) showDialog = true
            return
        }
        if (!manual && (!checker.shouldCheckAutomatically() || showDialog)) return
        if (manual) showDialog = true
        checking = true
        update = null
        downloaded = null
        message = null
        scope.launch {
            try {
                val available = checker.checkForUpdate()
                if (available != null && (showDialog || checker.canPrompt(available))) {
                    update = available
                    showDialog = true
                    if (!manual && autoDownload) {
                        downloading = true
                        try {
                            downloaded = checker.download(available)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = R.string.update_download_failed
                        } finally {
                            downloading = false
                        }
                    }
                } else if (showDialog) {
                    message = R.string.settings_update_latest
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (showDialog) message = R.string.update_check_failed
            } finally {
                checking = false
            }
        }
    }

    fun close() {
        if (downloading) return
        update?.let(checker::dismiss)
        showDialog = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { check(manual = false) }
    CompositionLocalProvider(
        LocalCheckForUpdates provides if (checker.isConfigured) ({ check(manual = true) }) else null,
        LocalAutoDownloadSetting provides if (checker.isConfigured) AutoDownloadSetting(autoDownload) {
            autoDownload = it
            checker.setAutoDownload(it)
        } else null
    ) {
        content()
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = ::close,
            title = { Text(stringResource(R.string.settings_check_updates)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    update?.let {
                        Text(stringResource(R.string.settings_update_available, it.latestVersion))
                        if (it.changelog.isNotBlank()) Text(it.changelog)
                    }
                    if (checking || downloading) {
                        CircularProgressIndicator()
                        Text(stringResource(if (checking) R.string.update_checking else R.string.update_downloading))
                    }
                    message?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                update?.let { info ->
                    Button(
                        enabled = !downloading,
                        onClick = {
                            downloading = true
                            message = null
                            scope.launch {
                                try {
                                    val apk = downloaded?.takeIf { it.isFile } ?: checker.download(info)
                                    downloaded = apk
                                    if (checker.install(apk)) {
                                        showDialog = false
                                    } else {
                                        message = R.string.update_allow_install
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = R.string.update_download_failed
                                } finally {
                                    downloading = false
                                }
                            }
                        }
                    ) {
                        Text(stringResource(if (downloaded == null) R.string.settings_update_download else R.string.update_install))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::close, enabled = !downloading) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}
