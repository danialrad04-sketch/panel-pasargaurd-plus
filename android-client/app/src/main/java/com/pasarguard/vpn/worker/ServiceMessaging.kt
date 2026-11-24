package com.pasarguard.vpn.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pasarguard.vpn.BuildConfig
import com.pasarguard.vpn.MainActivity
import com.pasarguard.vpn.R
import com.pasarguard.vpn.data.repository.SupportRepository
import com.pasarguard.vpn.ui.navigation.Routes
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@AndroidEntryPoint
class ServiceMessaging : FirebaseMessagingService() {
    @Inject lateinit var repository: SupportRepository

    override fun onNewToken(token: String) {
        enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"]
        val id = message.data["id"]?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return
        val revision = message.data["revision"]?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val route = when (kind) {
            "support" -> if (repository.signedIn()) Routes.SUPPORT else return
            "incident" -> Routes.SERVICE_STATUS
            else -> return
        }
        if (!notificationsAllowed(this)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = "pushed:$kind:$id"
        if (prefs.getLong(key, 0) >= revision) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_notifications),
                NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
        }
        val notificationId = key.hashCode()
        val pending = PendingIntent.getActivity(this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(notificationId, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (kind == "support") R.string.support_notification else R.string.service_notification))
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        prefs.edit().putLong(key, revision).apply()
    }

    companion object {
        private const val CHANNEL = "service_messages"
        internal const val PREFS = "service_messaging"
        internal const val SERVER = "server_id"
        internal val configured get() = BuildConfig.FCM_APP_ID.isNotBlank() && BuildConfig.FCM_API_KEY.isNotBlank() &&
            BuildConfig.FCM_PROJECT_ID.isNotBlank() && BuildConfig.FCM_SENDER_ID.isNotBlank()

        fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun refreshRegistration(context: Context, server: String?) {
            if (!configured || !notificationsAllowed(context)) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SERVER, server).apply()
            enqueue(context)
        }

        private fun enqueue(context: Context) {
            if (!configured || !notificationsAllowed(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork("service_notification_registration",
                ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ServiceRegistrationWorker>().build())
        }
    }
}

@HiltWorker
class ServiceRegistrationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: SupportRepository
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!repository.configured || !ServiceMessaging.configured || !ServiceMessaging.notificationsAllowed(applicationContext))
            return Result.success()
        return try {
            val app = FirebaseApp.getApps(applicationContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (app == null) {
                FirebaseApp.initializeApp(applicationContext, FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APP_ID).setApiKey(BuildConfig.FCM_API_KEY)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID).setGcmSenderId(BuildConfig.FCM_SENDER_ID).build())
            }
            // Token registration is tied to the active account on every foreground session.
            val token = suspendCancellableCoroutine<String> { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener {
                    if (it.isSuccessful) continuation.resume(it.result)
                    else continuation.resumeWithException(it.exception ?: IllegalStateException("FCM unavailable"))
                }
            }
            val server = applicationContext.getSharedPreferences(ServiceMessaging.PREFS, Context.MODE_PRIVATE)
                .getString(ServiceMessaging.SERVER, null)
            repository.register(token, server)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
