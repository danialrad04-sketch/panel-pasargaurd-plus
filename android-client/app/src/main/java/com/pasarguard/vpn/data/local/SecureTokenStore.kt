package com.pasarguard.vpn.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pasarguard.vpn.domain.model.SubscriptionCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores customer subscription credentials (panel base URL + subscription token)
 * encrypted at rest using Android Keystore AES256-GCM.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // Fallback only if Keystore is corrupted after OS restore; resets file cleanly
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun saveCredentials(credentials: SubscriptionCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, credentials.baseUrl.trimEnd('/'))
            .putString(KEY_SUB_TOKEN, credentials.token.trim())
            .apply()
    }

    fun getCredentials(): SubscriptionCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
        val token = prefs.getString(KEY_SUB_TOKEN, null)
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) return null
        return SubscriptionCredentials(baseUrl = baseUrl, token = token)
    }

    fun clear() {
        prefs.edit().remove(KEY_BASE_URL).remove(KEY_SUB_TOKEN).apply()
    }

    @Synchronized
    fun purchaseToken(): String {
        prefs.getString(KEY_PURCHASE_TOKEN, null)?.let { return it }
        val token = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(prefs.edit().putString(KEY_PURCHASE_TOKEN, token).commit()) {
            "Could not save the purchase credentials"
        }
        return token
    }

    companion object {
        private const val PREFS_FILE_NAME = "pasarguard_secure_prefs"
        private const val KEY_BASE_URL = "sub_base_url"
        private const val KEY_SUB_TOKEN = "sub_token"
        private const val KEY_PURCHASE_TOKEN = "purchase_token"
    }
}
