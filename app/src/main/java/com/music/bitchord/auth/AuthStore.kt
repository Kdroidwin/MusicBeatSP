package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest storage for credentials.
 *
 * Two live here: the YouTube Music session cookie, and — if the user turns on
 * the Discord integration — that account's own bearer token. Neither is a
 * password: the Google one is typed into accounts.google.com inside a WebView,
 * and the Discord one is read out of a completed login session. But both grant
 * full access to their account, so they don't go in the plain prefs the
 * scrobbler tokens use.
 *
 * Keystore init can fail on some devices. In that case credentials stay
 * unavailable instead of being written in plaintext.
 */
class AuthStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            appContext,
            "bitchord_auth",
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        // Do not include exception text: platform crypto exceptions can contain
        // device-specific details. More importantly, never fall back to disk
        // storage that can be read without the Android Keystore key.
        Log.w("BitChord", "Encrypted credential storage unavailable; account sign-in is disabled")
    }.getOrNull()

    val secureStorageAvailable: Boolean
        get() = prefs != null

    init {
        if (prefs != null) migrateLegacyPlaintext(appContext, prefs)
        else discardLegacyPlaintext(appContext)
    }

    var cookie: String?
        get() = prefs?.getString(KEY_COOKIE, null)
        set(value) { prefs?.edit()?.putString(KEY_COOKIE, value)?.apply() }

    /**
     * The durable account registry. Credentials remain in this encrypted store;
     * the old single-cookie entry is migrated lazily so an update never logs a
     * listener out.
     */
    var sessions: List<GoogleAccountSession>
        get() {
            val saved = sessionsFromJson(prefs?.getString(KEY_SESSIONS, null))
            if (saved.isNotEmpty()) return saved
            val legacy = cookie ?: return emptyList()
            val profile = YouTubeProfile(
                profileId = profileId(channelPageId, channelDataSyncId, channelName ?: "Personal"),
                name = channelName ?: "Personal",
                pageId = channelPageId,
                dataSyncId = channelDataSyncId,
                authUser = channelAuthUser,
                isBrandAccount = channelPageId != null,
            )
            return listOf(GoogleAccountSession(
                accountId = sessionId(legacy, channelDataSyncId), cookie = legacy,
                profiles = listOf(profile), activeProfileId = profile.profileId,
            )).also { replaceSessions(it) }
        }
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = prefs?.getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) { prefs?.edit()?.putString(KEY_ACTIVE_ACCOUNT, value)?.apply() }

    var activeProfileId: String?
        get() = prefs?.getString(KEY_ACTIVE_PROFILE, null)
        set(value) { prefs?.edit()?.putString(KEY_ACTIVE_PROFILE, value)?.apply() }

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        prefs?.edit()?.putString(KEY_SESSIONS, value.toJson())?.apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val next = sessions.filterNot { it.accountId == session.accountId } + session
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        prefs?.edit()?.putString(KEY_COOKIE, fallback?.cookie)?.apply()
        return fallback
    }

    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = prefs?.getString(KEY_DISCORD_TOKEN, null)
        set(value) { prefs?.edit()?.putString(KEY_DISCORD_TOKEN, value)?.apply() }

    /** Protected storage for other account and scrobbling credentials. */
    fun getProtectedValue(name: String): String? = prefs?.getString(protectedValueKey(name), null)

    fun setProtectedValue(name: String, value: String) {
        prefs?.edit()?.putString(protectedValueKey(name), value.ifEmpty { null })?.apply()
    }

    /** Synchronous write used only while moving an old plaintext preference. */
    fun migrateProtectedValue(name: String, value: String): Boolean {
        val securePrefs = prefs ?: return false
        return runCatching {
            securePrefs.edit().putString(protectedValueKey(name), value).commit()
        }.getOrDefault(false)
    }

    /**
     * The channel the listener chose to act as, if they chose one.
     *
     * Stored beside the cookie rather than in the plain settings because it is
     * only meaningful with that cookie and must not outlive it: a `dataSyncId`
     * from one login, replayed under another, is answered with 401 on every
     * request. [signOut] and [onNewSession] both clear it for that reason.
     *
     * A null [channelPageId] with a [channelDataSyncId] set is a real state,
     * not an absent one — it is the account's own channel, deliberately chosen
     * over a brand channel the web player would otherwise default to.
     */
    val channelPageId: String? get() = prefs?.getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = prefs?.getString(KEY_CHANNEL_DATASYNC_ID, null)

    /** The chosen channel's display name, for the settings row. */
    val channelName: String? get() = prefs?.getString(KEY_CHANNEL_NAME, null)

    /** Which Google account in the jar it belongs to; null means "as the shell says". */
    val channelAuthUser: String? get() = prefs?.getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) {
        prefs?.edit()?.apply {
            putString(KEY_CHANNEL_PAGE_ID, pageId)
            putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
            putString(KEY_CHANNEL_NAME, name)
            putString(KEY_CHANNEL_AUTH_USER, authUser)
        }?.apply()
    }

    /**
     * The chosen channel's name, once something knows it.
     *
     * Separate from [selectChannel] because the two are learned at different
     * times: a channel picked in the in-app browser is identified by ids the
     * moment it is picked, and named only after the next account fetch comes
     * back to say what it is called.
     */
    fun setChannelName(name: String?) =
        prefs?.edit()?.putString(KEY_CHANNEL_NAME, name)?.apply()

    /** Back to whichever channel YouTube Music serves by default. */
    fun clearChannel() {
        prefs?.edit()?.apply {
            remove(KEY_CHANNEL_PAGE_ID)
            remove(KEY_CHANNEL_DATASYNC_ID)
            remove(KEY_CHANNEL_NAME)
            remove(KEY_CHANNEL_AUTH_USER)
        }?.apply()
    }

    /**
     * A fresh login lands here. The cookie is new, so any channel chosen under
     * the old one names an identity this session cannot act as.
     */
    fun onNewSession(cookie: String) {
        this.cookie = cookie
        clearChannel()
    }

    /**
     * Signs out of YouTube Music only — the Discord login is a separate account.
     */
    fun signOut() {
        prefs?.edit()?.apply {
            remove(KEY_COOKIE)
            remove(KEY_SESSIONS)
            remove(KEY_ACTIVE_ACCOUNT)
            remove(KEY_ACTIVE_PROFILE)
        }?.apply()
        clearChannel()
        // The in-app browser keeps its own copy of the Google login, and a
        // sign-out that leaves it in place is not one: the next sign-in is
        // waved straight through as the account just signed out of, with no
        // opportunity to choose another. See [BrowserSession].
        BrowserSession.clearGoogleCookies()
    }

    companion object {
        private const val LEGACY_PLAIN_PREFS = "bitchord_auth_plain"

        /** Migrates old fallback data only after encrypted storage is available. */
        private fun migrateLegacyPlaintext(context: Context, encrypted: SharedPreferences) {
            val legacy = runCatching {
                context.getSharedPreferences(LEGACY_PLAIN_PREFS, Context.MODE_PRIVATE)
            }.getOrNull() ?: return
            val oldValues = runCatching { legacy.all }.getOrDefault(emptyMap())
            if (oldValues.isNotEmpty()) {
                val existingKeys = runCatching { encrypted.all.keys }.getOrDefault(emptySet())
                val editor = encrypted.edit()
                var changed = false
                oldValues.forEach { (key, value) ->
                    if (key !in existingKeys && value is String) {
                        editor.putString(key, value)
                        changed = true
                    }
                }
                // If migration cannot be committed, discard the plaintext copy
                // anyway; losing an unavailable login is safer than retaining a
                // bearer credential without encryption.
                val migrated = !changed || runCatching { editor.commit() }.getOrDefault(false)
                if (!migrated) Log.w("BitChord", "Could not migrate old credentials into encrypted storage")
            }
            discardLegacyPlaintext(context, legacy)
        }

        private fun discardLegacyPlaintext(context: Context, existing: SharedPreferences? = null) {
            runCatching {
                val legacy = existing ?: context.getSharedPreferences(LEGACY_PLAIN_PREFS, Context.MODE_PRIVATE)
                legacy.edit().clear().commit()
                context.deleteSharedPreferences(LEGACY_PLAIN_PREFS)
            }
        }

        /**
         * Whether a cookie header carries a secret Innertube requests can be
         * signed with.
         *
         * Matched on the cookie *name*, which reads as pedantry and is not. The
         * test used to be `cookie.contains("SAPISID")`, and `__Secure-3PAPISID`
         * contains "SAPISID" — so a jar holding only the `__Secure-` forms, which
         * is what a partitioned-cookie login produces, passed a check for a
         * cookie it did not have. The app then declared itself signed in and made
         * every request unsigned, which Google answers as a stranger. Library
         * reads degraded quietly and history was never written at all.
         */
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_COOKIE = "cookie"
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_PROTECTED_VALUE_PREFIX = "protected_value:"

        private fun protectedValueKey(name: String): String = "$KEY_PROTECTED_VALUE_PREFIX$name"
    }
}
