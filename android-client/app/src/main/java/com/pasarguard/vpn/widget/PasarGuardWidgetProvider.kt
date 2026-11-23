package com.pasarguard.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.MainActivity
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.vpn.service.PasarGuardVpnService

/**
 * Lightweight dark-glass Home-Screen Widget showing live connection status, active server,
 * and a 1-tap Connect / Disconnect button.
 */
class PasarGuardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PasarGuardWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.pasarguard.vpn.action.REFRESH_WIDGET"
        private const val PREFS_NAME = "pasarguard_widget_cache"
        private const val KEY_STATUS = "status"
        private const val KEY_SERVER = "server"
        private const val KEY_SUBTITLE = "subtitle"

        fun saveSnapshotAndRefresh(
            context: Context,
            status: ConnectionStatus,
            serverName: String?,
            subtitle: String?
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_STATUS, status.name)
                .putString(KEY_SERVER, serverName.orEmpty())
                .putString(KEY_SUBTITLE, subtitle.orEmpty())
                .apply()
            requestUpdate(context)
        }

        fun requestUpdate(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, PasarGuardWidgetProvider::class.java))
                for (id in ids) {
                    updateWidget(context, manager, id)
                }
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val status = runCatching {
                ConnectionStatus.valueOf(prefs.getString(KEY_STATUS, ConnectionStatus.DISCONNECTED.name)!!)
            }.getOrDefault(ConnectionStatus.DISCONNECTED)
            val server = prefs.getString(KEY_SERVER, "").orEmpty()
            val subtitle = prefs.getString(KEY_SUBTITLE, "").orEmpty()

            val views = RemoteViews(context.packageName, R.layout.widget_vpn_status)
            views.setTextViewText(R.id.widget_brand, BrandConfig.appName)

            val (statusLabel, actionLabel, statusColor) = when (status) {
                ConnectionStatus.CONNECTED -> Triple(
                    context.getString(R.string.status_connected),
                    context.getString(R.string.action_disconnect),
                    0xFF10B981.toInt()
                )

                ConnectionStatus.CONNECTING -> Triple(
                    context.getString(R.string.status_connecting),
                    context.getString(R.string.action_disconnect),
                    0xFFF59E0B.toInt()
                )

                else -> Triple(
                    context.getString(R.string.status_disconnected),
                    context.getString(R.string.action_connect),
                    0xFF94A3B8.toInt()
                )
            }

            views.setTextViewText(R.id.widget_status, statusLabel)
            views.setTextColor(R.id.widget_status, statusColor)
            views.setTextViewText(
                R.id.widget_server,
                server.ifBlank { subtitle.ifBlank { context.getString(R.string.auto_best_server) } }
            )
            views.setTextViewText(R.id.widget_toggle_btn, actionLabel)

            // Open app on root click
            val openAppIntent = PendingIntent.getActivity(
                context,
                301,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)

            // Toggle VPN on button click
            val togglePendingIntent = if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CONNECTING) {
                PendingIntent.getService(
                    context,
                    302,
                    Intent(context, PasarGuardVpnService::class.java).apply {
                        action = PasarGuardVpnService.ACTION_DISCONNECT
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    303,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_TOGGLE_VPN, true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
