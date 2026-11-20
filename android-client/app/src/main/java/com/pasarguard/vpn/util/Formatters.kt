package com.pasarguard.vpn.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.toArgb
import androidx.core.os.LocaleListCompat
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.AppLanguage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.lastIndex) {
            value /= 1024.0
            unitIdx++
        }
        return if (unitIdx <= 1) {
            "${value.toLong()} ${units[unitIdx]}"
        } else {
            String.format(Locale.US, "%.2f %s", value, units[unitIdx])
        }
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatEpochDate(epochSeconds: Long?, locale: Locale = Locale.getDefault()): String {
        if (epochSeconds == null || epochSeconds <= 0L) return "—"
        val sdf = SimpleDateFormat("yyyy/MM/dd", locale)
        return sdf.format(Date(epochSeconds * 1000L))
    }

    fun formatRelativeSync(epochMs: Long): String {
        val diffSec = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60L -> "${diffSec}s"
            diffSec < 3600L -> "${diffSec / 60L}m"
            diffSec < 86_400L -> "${diffSec / 3600L}h"
            else -> "${diffSec / 86_400L}d"
        }
    }

    fun toPersianDigitsIfNeeded(text: String, isPersian: Boolean): String {
        if (!isPersian) return text
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return buildString(text.length) {
            for (ch in text) {
                if (ch in '0'..'9') append(persianDigits[ch - '0']) else append(ch)
            }
        }
    }

    fun applyAppLanguage(language: AppLanguage) {
        val locales = LocaleListCompat.forLanguageTags(language.code)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun launchCustomTab(context: Context, url: String) {
        val uri = Uri.parse(url)
        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(BrandConfig.darkSurfaceElevated.toArgb())
            .setNavigationBarColor(BrandConfig.darkBackground.toArgb())
            .build()

        runCatching {
            CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
                .launchUrl(context, uri)
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun openExternalUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openSystemVpnSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
