package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    fun read(sourceId: String, configuredUrl: String): RemoteServerCredentials? {
        val prefs = encryptedPrefs ?: return null
        return runCatching {
            val expectedOrigin = originOf(configuredUrl) ?: return null
            val storedOrigin = prefs.getString(originKey(sourceId), null)
            if (storedOrigin == null) {
                // Bind credentials created before origin binding was introduced
                // to the source's current saved endpoint once. Every later edit
                // to that endpoint makes the old credentials unavailable.
                if (!prefs.edit().putString(originKey(sourceId), expectedOrigin).commit()) return null
            } else if (storedOrigin != expectedOrigin) {
                return null
            }
            val username = prefs.getString(usernameKey(sourceId), null) ?: return null
            val password = prefs.getString(passwordKey(sourceId), null) ?: return null
            RemoteServerCredentials(username, password)
        }.getOrNull()
    }

    fun save(sourceId: String, configuredUrl: String, credentials: RemoteServerCredentials): Boolean {
        val prefs = encryptedPrefs ?: return false
        val origin = originOf(configuredUrl) ?: return false
        return runCatching {
            prefs.edit()
                .putString(usernameKey(sourceId), credentials.username)
                .putString(passwordKey(sourceId), credentials.password)
                .putString(originKey(sourceId), origin)
                .commit()
        }.getOrDefault(false)
    }

    fun delete(sourceId: String): Boolean {
        val prefs = encryptedPrefs ?: return false
        return runCatching {
            prefs.edit()
                .remove(usernameKey(sourceId))
                .remove(passwordKey(sourceId))
                .remove(originKey(sourceId))
                .commit()
        }.getOrDefault(false)
    }

    private fun usernameKey(id: String) = "user:$id"
    private fun passwordKey(id: String) = "password:$id"
    private fun originKey(id: String) = "origin:$id"

    private fun originOf(value: String): String? = value.trim().toHttpUrlOrNull()?.let { url ->
        if (url.scheme !in setOf("https", "http") || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            null
        } else {
            "${url.scheme}://${url.host}:${url.port}"
        }
    }

    private const val PREFS_NAME = "music_server_credentials_encrypted"
}
