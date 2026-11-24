package com.pasarguard.vpn.ui.screens.settings

import android.content.Intent
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.R
import com.pasarguard.vpn.ui.components.DeepSpaceBackground
import com.pasarguard.vpn.ui.components.EmptyStateView
import com.pasarguard.vpn.ui.components.GlassCard
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.util.SanitizedLogBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val logs by SanitizedLogBuffer.logs.collectAsStateWithLifecycle()
    val colors = PasarGuardTheme.colors
    val context = LocalContext.current
    var levelFilter by remember { mutableStateOf<String?>(null) }

    val filtered = remember(logs, levelFilter) {
        val base = if (levelFilter == null) logs else logs.filter { it.level == levelFilter }
        base.reversed()
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

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
                        text = stringResource(R.string.logs_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                }
                Row {
                    IconButton(onClick = {
                        val report = SanitizedLogBuffer.exportReport(
                            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            coreVersion = "Xray-core + sing-box"
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Donino Vpn Sanitized Diagnostics")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, tint = colors.heroEnd)
                    }
                    IconButton(onClick = { SanitizedLogBuffer.clear() }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = colors.statusDanger)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = levelFilter == null,
                    onClick = { levelFilter = null },
                    label = { Text(stringResource(R.string.servers_filter_all)) }
                )
                FilterChip(
                    selected = levelFilter == "WARN",
                    onClick = { levelFilter = "WARN" },
                    label = { Text("WARN") }
                )
                FilterChip(
                    selected = levelFilter == "ERROR",
                    onClick = { levelFilter = "ERROR" },
                    label = { Text("ERROR") }
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            if (filtered.isEmpty()) {
                EmptyStateView(
                    title = stringResource(R.string.logs_empty_title),
                    description = stringResource(R.string.logs_empty_desc)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered) { entry ->
                        val badgeColor = when (entry.level) {
                            "ERROR" -> colors.statusDanger
                            "WARN" -> colors.statusWarning
                            else -> colors.heroEnd
                        }
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Text(
                                text = "${timeFmt.format(Date(entry.timestampMs))} [${entry.level}] ${entry.tag}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = badgeColor
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
