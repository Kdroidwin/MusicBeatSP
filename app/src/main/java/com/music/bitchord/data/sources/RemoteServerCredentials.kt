package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Credentials are kept out of exported settings and server URLs. */
data class RemoteServerCredentials(
    val username: String,
    val password: String,
)

/**
 * Strict encrypted storage for remote-server credentials.
 *
 * Unlike ordinary source settings, credentials are never written to a
 * plaintext fallback if Android Keystore is unavailable. In that case saving
 * a server fails with a user-visible error instead.
 */
internal object RemoteServerCredentialsStore {
    @Volatile private var encryptedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        encryptedPrefs = runCatching {
            val key = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    fun read(sourceId: String): RemoteServerCredentials? {
        val prefs = encryptedPrefs ?: return null
        return runCatching {
            val username = prefs.getString(usernameKey(sourceId), null) ?: return null
            val password = prefs.getString(passwordKey(sourceId), null) ?: return null
            RemoteServerCredentials(username, password)
        }.getOrNull()
    }

    fun save(sourceId: String, credentials: RemoteServerCredentials): Boolean {
        val prefs = encryptedPrefs ?: return false
        return runCatching {
            prefs.edit()
                .putString(usernameKey(sourceId), credentials.username)
                .putString(passwordKey(sourceId), credentials.password)
                .commit()
        }.getOrDefault(false)
    }

    fun delete(sourceId: String): Boolean {
        val prefs = encryptedPrefs ?: return false
        return runCatching {
            prefs.edit()
                .remove(usernameKey(sourceId))
                .remove(passwordKey(sourceId))
                .commit()
        }.getOrDefault(false)
    }

    private fun usernameKey(id: String) = "user:$id"
    private fun passwordKey(id: String) = "password:$id"

    private const val PREFS_NAME = "music_server_credentials_encrypted"
}
