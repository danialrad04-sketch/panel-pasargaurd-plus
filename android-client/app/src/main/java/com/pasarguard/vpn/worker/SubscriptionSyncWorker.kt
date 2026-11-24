package com.pasarguard.vpn.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.MainActivity
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.ui.navigation.Routes
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val vpnChannel = NotificationChannel(
            CHANNEL_VPN,
            context.getString(R.string.notif_channel_vpn_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_vpn_desc)
            setShowBadge(false)
        }

        val expiryChannel = NotificationChannel(
            CHANNEL_EXPIRY_ALERTS,
            context.getString(R.string.notif_channel_expiry_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_expiry_desc)
        }

        val trafficChannel = NotificationChannel(
            CHANNEL_TRAFFIC_ALERTS,
            context.getString(R.string.notif_channel_traffic_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_traffic_desc)
        }

        val orderChannel = NotificationChannel(
            CHANNEL_ORDER_UPDATES,
            context.getString(R.string.notif_channel_orders_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_orders_desc)
        }

        nm.createNotificationChannel(vpnChannel)
        nm.createNotificationChannel(expiryChannel)
        nm.createNotificationChannel(trafficChannel)
        nm.createNotificationChannel(orderChannel)
    }

    fun buildVpnNotification(
        title: String,
        subtitle: String,
        contentIntent: PendingIntent,
        disconnectIntent: PendingIntent,
        disguise: Boolean = false
    ): Notification {
        ensureChannels()
        val effectiveTitle = if (disguise) context.getString(R.string.notif_disguise_title) else title
        val effectiveSubtitle = if (disguise) context.getString(R.string.notif_disguise_subtitle) else subtitle
        val builder = NotificationCompat.Builder(context, CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(effectiveTitle)
            .setContentText(effectiveSubtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)

        if (!disguise) {
            builder.addAction(
                R.drawable.ic_vpn_shield_notif,
                context.getString(R.string.notif_action_disconnect),
                disconnectIntent
            )
        }
        return builder.build()
    }

    fun notifyVpnStatus(notification: Notification) {
        if (!canPostNotifications()) return
        NotificationManagerCompat.from(context).notify(NOTIF_ID_VPN, notification)
    }

    fun showExpiryWarning(daysLeft: Int): Boolean {
        if (!canPostNotifications() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannels()
        val renewIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, Routes.RENEWAL)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (daysLeft <= 0) {
            context.getString(R.string.notif_expiry_expired_body)
        } else {
            context.getString(R.string.notif_expiry_body, daysLeft)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_EXPIRY_ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(context.getString(R.string.notif_expiry_title))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(renewIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_EXPIRY, notification)
        return true
    }

    fun showLowTrafficWarning(usedPercent: Int): Boolean {
        if (!canPostNotifications() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannels()
        val renewIntent = PendingIntent.getActivity(
            context,
            11,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, Routes.RENEWAL)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (usedPercent >= 100) {
            context.getString(R.string.notif_low_traffic_exhausted_body)
        } else {
            context.getString(R.string.notif_low_traffic_used_body, usedPercent)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_TRAFFIC_ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(context.getString(R.string.notif_low_traffic_title))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(renewIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_TRAFFIC, notification)
        return true
    }

    fun showOrderApprovedNotification(orderId: String, planTitle: String) {
        if (!canPostNotifications()) return
        ensureChannels()
        val ordersIntent = PendingIntent.getActivity(
            context,
            12,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, Routes.ORDERS)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ORDER_UPDATES)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(context.getString(R.string.notif_order_approved_title))
            .setContentText(context.getString(R.string.notif_order_approved_body, planTitle, orderId))
            .setAutoCancel(true)
            .setContentIntent(ordersIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_ORDER, notification)
    }

    fun cancelSubscriptionAlerts() {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_EXPIRY)
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_TRAFFIC)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_VPN = "channel_vpn_status"
        const val CHANNEL_EXPIRY_ALERTS = "channel_expiry_alerts"
        const val CHANNEL_TRAFFIC_ALERTS = "channel_traffic_alerts"
        const val CHANNEL_ORDER_UPDATES = "channel_order_updates"
        const val NOTIF_ID_VPN = 1001
        const val NOTIF_ID_EXPIRY = 2001
        const val NOTIF_ID_TRAFFIC = 2002
        const val NOTIF_ID_ORDER = 2003
    }
}

/**
 * Periodically refreshes the PasarGuard subscription and triggers multi-threshold
 * Expiry (7d / 3d / 1d / expired) and Low-Traffic (80% / 90% / 100%) alerts with
 * direct deep links to the Renewal screen.
 */
@HiltWorker
class SubscriptionSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val credentials = subscriptionRepository.getSavedCredentials() ?: return Result.success()
        val sub = subscriptionRepository.syncSubscription().getOrNull() ?: return Result.retry()
        if (subscriptionRepository.getSavedCredentials() != credentials) return Result.success()
        val settings = settingsRepository.getSnapshot()
        val alerts = applicationContext.getSharedPreferences("subscription_reminders", Context.MODE_PRIVATE)
        val accountKey = MessageDigest.getInstance("SHA-256").digest(credentials.fullSubscriptionUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val expiry = sub.expireEpochSeconds
        if (settings.expiryWarningEnabled && expiry != null &&
            sub.status in setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.LIMITED, SubscriptionStatus.EXPIRED)) {
            val seconds = (expiry - System.currentTimeMillis() / 1000L).coerceAtLeast(0L)
            val days = ((seconds + 86_399L) / 86_400L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val stage = when {
                sub.status == SubscriptionStatus.EXPIRED || days == 0 -> 0
                days <= 1 -> 1
                days <= 3 -> 3
                days <= 7 -> 7
                else -> null
            }
            val expiryKey = "$accountKey:expiry"
            val stageKey = "$accountKey:expiry-stage"
            val last = if (alerts.getLong(expiryKey, -1L) == expiry) alerts.getInt(stageKey, Int.MAX_VALUE)
                else Int.MAX_VALUE
            val daysToReport = if (sub.status == SubscriptionStatus.EXPIRED) 0 else days
            if (stage != null && stage < last && notificationHelper.showExpiryWarning(daysToReport)) {
                alerts.edit().putLong(expiryKey, expiry).putInt(stageKey, stage).apply()
            }
        }

        val quota = sub.dataLimitBytes
        if (settings.lowTrafficWarningEnabled && quota != null && quota > 0L &&
            sub.status in setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.LIMITED)) {
            val used = (sub.usedTrafficBytes.toDouble() * 100 / quota).toInt().coerceIn(0, 100)
            val stage = when {
                sub.status == SubscriptionStatus.LIMITED || used >= 100 -> 100
                used >= 90 -> 90
                used >= 80 -> 80
                else -> 0
            }
            val quotaKey = "$accountKey:quota"
            val stageKey = "$accountKey:quota-stage"
            val last = if (alerts.getLong(quotaKey, 0L) == quota) alerts.getInt(stageKey, 0) else 0
            if (stage == 0) {
                if (last != 0) alerts.edit().remove(stageKey).apply()
            } else if (stage > last && notificationHelper.showLowTrafficWarning(if (stage == 100) 100 else used)) {
                alerts.edit().putLong(quotaKey, quota).putInt(stageKey, stage).apply()
            }
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "pasarguard_sub_periodic_sync"

        fun schedule(
            context: Context,
            intervalHours: Long = BrandConfig.autoRefreshIntervalHours
        ) {
            val wm = WorkManager.getInstance(context)
            if (intervalHours <= 0L) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val safeHours = intervalHours.coerceIn(1L, 168L)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SubscriptionSyncWorker>(
                safeHours,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
