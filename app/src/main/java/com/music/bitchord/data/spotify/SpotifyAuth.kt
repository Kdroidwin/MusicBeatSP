package com.music.bitchord.data.spotify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.spotify.sdk.android.auth.PKCEInformation
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** ログインが必要(まだしていない・取り消された)ことを表す */
class SpotifyLoginRequired : IOException("Spotify login required")

/**
 * Spotify へのログイン(Spotify 公式の認証ライブラリを使う。PKCE なので、クライアントシークレットは使わない)。
 * クライアント ID とトークンは、この端末のアプリ内(SharedPreferences)だけに保存する。
 * ソースコードやリポジトリには、ID もトークンも書かない
 */
object SpotifyAuth {
    const val REDIRECT_URI = "musicbeatsp-spotify://callback"
    /** app-remote-control: 公式 Spotify アプリでの再生の操作(App Remote)に必要 */
    const val REMOTE_SCOPE = "app-remote-control"
    /** Only request permissions currently used by the Spotify library screen and App Remote playback. */
    private const val SCOPES = "$REMOTE_SCOPE user-library-read"
    private const val PREFS = "spotify"
    private const val K_CLIENT = "clientId"
    private const val K_REFRESH = "refreshToken"
    private const val K_ACCESS = "accessToken"
    private const val K_EXPIRES = "expiresAt"
    private const val K_LOGGED_IN = "loggedIn"
    private const val K_SCOPE = "scope"
    private const val K_PKCE_VERIFIER = "pkceVerifier"
    private const val K_AUTH_STATE = "authState"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(ctx: Context): String = prefs(ctx).getString(K_CLIENT, "").orEmpty()

    fun setClientId(ctx: Context, id: String) {
        val v = id.trim()
        if (v == clientId(ctx)) return
        // ID を変えたら、前の ID で得たトークンは使えないので消す
        prefs(ctx).edit().putString(K_CLIENT, v).remove(K_REFRESH).remove(K_ACCESS).remove(K_EXPIRES)
            .remove(K_SCOPE).remove(K_LOGGED_IN).remove(K_PKCE_VERIFIER).remove(K_AUTH_STATE).apply()
    }

    fun isLoggedIn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_LOGGED_IN, false)

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove(K_REFRESH).remove(K_ACCESS).remove(K_EXPIRES).remove(K_SCOPE)
            .remove(K_LOGGED_IN).remove(K_PKCE_VERIFIER).remove(K_AUTH_STATE).apply()
    }

    /** ログインで許可された範囲に、このアプリが使う許可がすべて入っているか(機能を増やしたら、ログインし直してもらう) */
    fun hasAllScopes(ctx: Context): Boolean =
        prefs(ctx).getString(K_SCOPE, "").orEmpty().split(' ').containsAll(SCOPES.split(' '))

    /**
     * ログインの依頼(Spotify 公式の認証ライブラリに渡す)。Spotify アプリが入っていれば、Spotify アプリの画面で許可する。
     * そうすると、公式アプリでの再生の操作(App Remote)も許可される(ブラウザでのログインだけでは許可されない)
     */
    fun request(ctx: Context): AuthorizationRequest {
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(24)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        prefs(ctx).edit().putString(K_PKCE_VERIFIER, verifier).putString(K_AUTH_STATE, state).apply()
        return AuthorizationRequest.Builder(clientId(ctx), AuthorizationResponse.Type.CODE, REDIRECT_URI)
            .setScopes(SCOPES.split(' ').toTypedArray())
            .setState(state)
            .setPkceInformation(PKCEInformation.sha256(verifier, challenge))
            .build()
    }

    /** ログインの結果を保存する。失敗や取り消しなら、その理由の文章を投げる */
    fun finishLogin(ctx: Context, r: AuthorizationResponse) {
        when (r.type) {
            AuthorizationResponse.Type.CODE -> {
                val p = prefs(ctx)
                val expectedState = p.getString(K_AUTH_STATE, null)
                val verifier = p.getString(K_PKCE_VERIFIER, null)
                val code = r.code?.takeIf { it.isNotBlank() }
                p.edit().remove(K_AUTH_STATE).remove(K_PKCE_VERIFIER).apply()
                if (expectedState.isNullOrBlank() || expectedState != r.state) {
                    throw IOException("Spotify authorization state did not match. Please try again.")
                }
                if (verifier.isNullOrBlank() || code == null) {
                    throw IOException("Spotify authorization code is missing. Please try again.")
                }
                val json = tokenRequest(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to clientId(ctx),
                    "code_verifier" to verifier,
                )
                save(ctx, json)
                if (p.getString(K_ACCESS, null).isNullOrBlank() || p.getString(K_REFRESH, null).isNullOrBlank()) {
                    logout(ctx)
                    throw IOException("Spotify did not return the tokens needed to finish sign-in.")
                }
                p.edit().putString(K_SCOPE, SCOPES).putBoolean(K_LOGGED_IN, true).apply()
            }
            AuthorizationResponse.Type.ERROR -> throw IOException(
                if (r.error == "access_denied") "Spotify login was cancelled."
                else "Spotify login failed: ${r.error.orEmpty()}",
            )
            AuthorizationResponse.Type.CANCELLED -> throw IOException("Spotify login was cancelled.")
            else -> throw IOException("Spotify login failed. Please try again.")
        }
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Web API 用のアクセストークン。期限が近ければ更新する(通信するので IO スレッドで呼ぶ) */
    @Synchronized
    fun accessToken(ctx: Context, forceRefresh: Boolean = false): String {
        val p = prefs(ctx)
        if (!isLoggedIn(ctx)) throw SpotifyLoginRequired()
        val access = p.getString(K_ACCESS, null)
        if (!forceRefresh && !access.isNullOrEmpty() && System.currentTimeMillis() < p.getLong(K_EXPIRES, 0) - 60_000) return access
        val refresh = p.getString(K_REFRESH, null)
        if (refresh.isNullOrEmpty()) { logout(ctx); throw SpotifyLoginRequired() }
        val json = try {
            tokenRequest("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(ctx))
        } catch (e: TokenRejected) {
            // 連携を解除された・ID が変わったなど。もう一度ログインしてもらう
            logout(ctx)
            throw SpotifyLoginRequired()
        }
        save(ctx, json)
        return p.getString(K_ACCESS, null) ?: throw SpotifyLoginRequired()
    }

    private fun save(ctx: Context, json: JSONObject) {
        val access = json.optString("access_token")
        if (access.isEmpty()) return
        val e = prefs(ctx).edit()
            .putString(K_ACCESS, access)
            .putLong(K_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
        // 更新のときは、新しいリフレッシュトークンが返らないこともある(そのときは今のものを使い続ける)
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { e.putString(K_REFRESH, it) }
        // 許可された範囲(更新のときは返らないこともある)
        json.optString("scope").takeIf { it.isNotEmpty() }?.let { e.putString(K_SCOPE, it) }
        e.apply()
    }

    private class TokenRejected(msg: String) : IOException(msg)

    private fun tokenRequest(vararg form: Pair<String, String>): JSONObject {
        val body = form.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.use { String(it.readBytes()) } }.getOrNull().orEmpty()
                val desc = runCatching { JSONObject(err).let { it.optString("error_description").ifEmpty { it.optString("error") } } }
                    .getOrNull().orEmpty().ifEmpty { err.take(200) }
                if (code == 400 || code == 401) throw TokenRejected("HTTP $code $desc".trim())
                throw IOException("HTTP $code $desc".trim())
            }
            return JSONObject(c.inputStream.use { String(it.readBytes()) })
        } finally {
            c.disconnect()
        }
    }

    /** このアプリの署名の SHA-1(Spotify の Developer Dashboard に登録する値) */
    fun signingSha1(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) emptyArray()
            else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = certs?.lastOrNull() ?: return@runCatching ""
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")
}
