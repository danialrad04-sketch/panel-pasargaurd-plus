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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
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

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
        }

        nm.createNotificationChannel(vpnChannel)
        nm.createNotificationChannel(alertsChannel)
    }

    fun buildVpnNotification(
        title: String,
        subtitle: String,
        contentIntent: PendingIntent,
        disconnectIntent: PendingIntent
    ): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_vpn_shield_notif,
                context.getString(R.string.notif_action_disconnect),
                disconnectIntent
            )
            .build()
    }

    fun notifyVpnStatus(notification: Notification) {
        if (!canPostNotifications()) return
        NotificationManagerCompat.from(context).notify(NOTIF_ID_VPN, notification)
    }

    fun showExpiryWarning(daysLeft: Int) {
        if (!canPostNotifications()) return
        ensureChannels()
        val intent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(context.getString(R.string.notif_expiry_title))
            .setContentText(context.getString(R.string.notif_expiry_body, daysLeft))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_EXPIRY, notification)
    }

    fun showLowTrafficWarning(percentThreshold: Int) {
        if (!canPostNotifications()) return
        ensureChannels()
        val intent = PendingIntent.getActivity(
            context,
            11,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(context.getString(R.string.notif_low_traffic_title))
            .setContentText(context.getString(R.string.notif_low_traffic_body, percentThreshold))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_TRAFFIC, notification)
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
        const val CHANNEL_ALERTS = "channel_subscription_alerts"
        const val NOTIF_ID_VPN = 1001
        const val NOTIF_ID_EXPIRY = 2001
        const val NOTIF_ID_TRAFFIC = 2002
    }
}

/**
 * Periodically refreshes the PasarGuard subscription and server configs in the background,
 * and triggers local Expiry / Low-Traffic alerts when enabled by the customer.
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
        if (subscriptionRepository.getSavedCredentials() == null) return Result.success()

        val syncResult = subscriptionRepository.syncSubscription()
        val sub = syncResult.getOrNull() ?: return Result.retry()
        val settings = settingsRepository.getSnapshot()

        if (sub.status == SubscriptionStatus.ACTIVE) {
            if (settings.expiryWarningEnabled) {
                val daysLeft = sub.remainingDays()
                if (daysLeft != null && daysLeft <= BrandConfig.expiryWarningDaysThreshold) {
                    notificationHelper.showExpiryWarning(daysLeft.coerceAtLeast(1))
                }
            }
            if (settings.lowTrafficWarningEnabled && !sub.isUnlimitedTraffic) {
                val pctRemaining = (sub.remainingTrafficFraction * 100f).toInt()
                if (pctRemaining <= BrandConfig.lowTrafficWarningPercentThreshold) {
                    notificationHelper.showLowTrafficWarning(BrandConfig.lowTrafficWarningPercentThreshold)
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "pasarguard_sub_periodic_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SubscriptionSyncWorker>(
                BrandConfig.autoRefreshIntervalHours,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
