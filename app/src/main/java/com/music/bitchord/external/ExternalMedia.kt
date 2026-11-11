package com.music.bitchord.external

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.music.bitchord.data.settings.AppSettings
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * ほかの音楽アプリ(Spotify・Qobuz・Amazon Music・YouTube Music など)の再生を、このアプリから表示・操作する。
 *
 * Android の「メディアセッション」を読むので、ストリーミングサービスごとの対応は要らない。
 * ほかのアプリのメディアセッションを読むには「通知へのアクセス」の許可が要る(通知の中身は読まない)。
 * 音はそのアプリから、いつもどおり Android を通して出る(このアプリの USB DAC 直接出力は通らない)。
 */
object ExternalMedia {
    /** 再生中(または最後に再生していた)ほかのアプリの曲(中身が同じなら等しいとみなし、画面を描き直さない) */
    data class NowPlaying(
        val packageName: String,
        val appName: String,
        val title: String,
        val artist: String,
        val album: String,
        val art: ImageBitmap?,
        val durationMs: Long,
        val playing: Boolean,
        /** 再生位置の基準(この時刻 at にこの位置 posMs。再生中は speed の速さで進む) */
        val posMs: Long,
        val at: Long,
        val speed: Float,
        val canSeek: Boolean,
        val canPrev: Boolean,
        val canNext: Boolean,
    ) {
        fun positionNow(): Long {
            if (!playing) return posMs
            val p = posMs + ((SystemClock.elapsedRealtime() - at) * speed).toLong()
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    /** 表示する曲(ほかのアプリが何も再生していなければ null) */
    var now by mutableStateOf<NowPlaying?>(null)
        private set

    /** 「通知へのアクセス」が許可されているか */
    var permitted by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var enabled = false
    private var artKey: Any? = null
    private var artCache: ImageBitmap? = null
    /** External session metadata is untrusted; image traffic gets no credentials and cannot target LAN hosts. */
    private val artClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE)
            .addInterceptor { chain ->
                if (AppSettings.offlineMode.value) throw IOException("Offline mode is enabled")
                chain.proceed(chain.request())
            }
            .dns(Dns { hostname ->
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any { !it.isPubliclyRoutable() }) {
                    throw UnknownHostException("Artwork host does not resolve to public addresses")
                }
                addresses
            })
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (AppSettings.offlineMode.value || !url.isHttps || url.port != 443 || url.encodedUsername.isNotEmpty() ||
                    url.encodedPassword.isNotEmpty() || url.host.isIpLiteral()
                ) {
                    throw IOException("External artwork must use a public HTTPS host while online")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            update()
            // 一時停止したら、ほかに再生中のアプリがあればそちらを表示する
            if (state?.state != PlaybackState.STATE_PLAYING) refresh()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = refresh()
    }

    private fun component(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    /** 「通知へのアクセス」が許可されているか(許可されたサービスの一覧に、このアプリのものがあるか) */
    private fun hasPermission(ctx: Context): Boolean {
        val expected = component(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            val notificationManager = ctx.getSystemService(android.app.NotificationManager::class.java)
            val granted = runCatching {
                notificationManager?.isNotificationListenerAccessGranted(expected)
            }.getOrNull()
            if (granted != null) return granted
        }
        val list = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /**
     * 使うかどうかを設定に合わせる。画面に戻ってきたとき(許可を変えたかもしれない)にも呼ぶ。
     * メインスレッドから呼ぶ
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        val c = ctx.applicationContext
        app = c
        permitted = hasPermission(c)
        val want = on && permitted
        if (want == enabled) {
            if (want) refresh()
            return
        }
        enabled = want
        if (want) {
            val m = androidx.core.content.ContextCompat.getSystemService(c, MediaSessionManager::class.java) ?: return
            manager = m
            runCatching { m.addOnActiveSessionsChangedListener(sessionsListener, component(c), main) }
            refresh()
        } else {
            manager?.let { m -> runCatching { m.removeOnActiveSessionsChangedListener(sessionsListener) } }
            manager = null
            attach(null)
        }
    }

    /** 今あるメディアセッションを読み直す */
    fun refresh() {
        val c = app ?: return
        val m = manager ?: return
        if (!enabled) return
        val list = runCatching { m.getActiveSessions(component(c)) }.getOrElse {
            // 許可が取り消された
            permitted = false
            emptyList()
        }
        pick(list)
    }

    /** 表示するアプリを選ぶ: 再生中のものを優先し、なければいちばん最近のもの(自分のアプリは除く) */
    private fun pick(list: List<MediaController>) {
        val self = app?.packageName
        val others = list.filter { it.packageName != self }
        val chosen = others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: others.firstOrNull { it.packageName == controller?.packageName }
            ?: others.firstOrNull { it.metadata != null }
        attach(chosen)
    }

    private fun attach(c: MediaController?) {
        val old = controller
        if (old != null && c != null && old.sessionToken == c.sessionToken) {
            update()
            return
        }
        old?.let { runCatching { it.unregisterCallback(callback) } }
        controller = c
        c?.let { runCatching { it.registerCallback(callback, main) } }
        update()
    }

    private fun update() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            now = null
            return
        }
        val st = c.playbackState
        val actions = st?.actions ?: 0L
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val n = NowPlaying(
            packageName = c.packageName,
            appName = appName(c.packageName),
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            art = hiArt(md) ?: art(md),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            playing = st?.state == PlaybackState.STATE_PLAYING,
            posMs = st?.position?.coerceAtLeast(0) ?: 0L,
            at = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
        // 何も変わっていなければ入れ替えない(画面を描き直さない)
        if (n != now) now = n
    }

    // ---- 高画質のジャケット ----
    // メディアセッションに入っている画像は小さく縮められていることが多い(Android が大きさを制限する)ので、
    // アプリが画像の URI(URL)を示していれば、そちらから元の大きさで読み直す

    private val artLoader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** 読み込んだ高画質の画像(元の URI と組で持つ) */
    @Volatile private var hiArtFor: String? = null
    @Volatile private var hiArtImage: ImageBitmap? = null
    /** 読み込み中・読み込みに失敗した URI(同じものを何度も読みに行かない) */
    private var hiArtTried: String? = null

    private fun artUri(md: MediaMetadata): String? =
        listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { md.getString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }

    /** 高画質の画像があればそれ。まだなら読み込みを始めて null(読み終わったら表示を更新する) */
    private fun hiArt(md: MediaMetadata): ImageBitmap? {
        val uri = artUri(md) ?: return null
        if (uri == hiArtFor) return hiArtImage
        if (uri == hiArtTried) return null
        hiArtTried = uri
        val c = app ?: return null
        artLoader.execute {
            val img = runCatching { loadArt(c, uri) }.getOrNull()
            main.post {
                if (img != null) {
                    hiArtFor = uri
                    hiArtImage = img
                    // 曲が変わっていなければ表示を差し替える
                    if (controller?.metadata?.let { artUri(it) } == uri) update()
                }
            }
        }
        return null
    }

    /** 画像を読む(大きすぎる画像は 1600px 程度に縮める) */
    private fun loadArt(ctx: Context, uri: String): ImageBitmap? {
        for (u in ArtUrls.candidates(uri)) {
            val bytes = runCatching { readBytes(ctx, u) }.getOrNull() ?: continue
            val bmp = decode(bytes) ?: continue
            return bmp.asImageBitmap()
        }
        return null
    }

    private fun readBytes(ctx: Context, u: String): ByteArray? {
        val max = 16 shl 20
        if (u.startsWith("http://", ignoreCase = true)) return null
        if (u.startsWith("https://", ignoreCase = true)) {
            val url = u.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.port == 443 && it.encodedUsername.isEmpty() &&
                    it.encodedPassword.isEmpty() && !it.host.isIpLiteral()
            } ?: return null
            return runCatching {
                artClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "MusicBeatSP")
                        .build(),
                ).execute().use { response ->
                    val body = response.body ?: return@use null
                    if (!response.isSuccessful || body.contentLength() > max) null
                    else body.byteStream().use { it.readLimited(max) }
                }
            }.getOrNull()
        }
        // content:// など(読む許可が無ければ例外になり、元の小さな画像のままにする)
        return ctx.contentResolver.openInputStream(android.net.Uri.parse(u))?.use { it.readLimited(max) }
    }

    private fun java.io.InputStream.readLimited(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    private fun String.isIpLiteral(): Boolean =
        contains(':') || matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))

    private fun InetAddress.isPubliclyRoutable(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return false
        }
        return when (this) {
            is Inet4Address -> {
                val octets = address.map { it.toInt() and 0xff }
                val first = octets[0]
                val second = octets[1]
                when {
                    first == 0 || first == 10 || first == 127 || first >= 224 -> false
                    first == 100 && second in 64..127 -> false // Shared address space (RFC 6598)
                    first == 169 && second == 254 -> false
                    first == 172 && second in 16..31 -> false
                    first == 192 && (second == 0 || second == 168) -> false
                    first == 192 && second == 88 && octets[2] == 99 -> false
                    first == 198 && second in 18..19 -> false
                    first == 198 && second == 51 && octets[2] == 100 -> false
                    first == 203 && second == 0 && octets[2] == 113 -> false
                    else -> true
                }
            }
            is Inet6Address -> {
                val bytes = address
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                // Only global unicast (2000::/3), excluding the documentation
                // prefix and the deprecated Teredo transition prefix.
                (first and 0xe0) == 0x20 &&
                    !(first == 0x20 && second == 0x01 && bytes[2].toInt() == 0x0d && bytes[3].toInt() == 0xb8) &&
                    !(first == 0x20 && second == 0x01 && bytes[2].toInt() == 0x00 && bytes[3].toInt() == 0x00)
            }
            else -> false
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val d = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d) ?: return null
        // 間引いたあとでも 3200px 近く残ることがあるので、1600px までに縮める(見た目は変わらず、メモリーは 1/4 になる)
        val m = maxOf(bmp.width, bmp.height)
        if (m <= 1600) return bmp
        val k = 1600f / m
        val out = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** ジャケット(同じ画像なら変換し直さない) */
    private fun art(md: MediaMetadata): ImageBitmap? {
        val b: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (b == null) {
            artKey = null; artCache = null
            return null
        }
        // 読み直すたびに画像は新しいものとして届くので、同じ曲で同じ大きさの画像なら、変換し直さずに前のものを使う
        val key = listOf(
            controller?.packageName, md.getString(MediaMetadata.METADATA_KEY_TITLE), md.getString(MediaMetadata.METADATA_KEY_ARTIST),
            md.getString(MediaMetadata.METADATA_KEY_ALBUM), b.width, b.height,
        ).joinToString("\u0000")
        if (key != artKey) {
            artKey = key
            artCache = runCatching { b.asImageBitmap() }.getOrNull()
        }
        return artCache
    }

    /** アプリの名前(調べるのに時間がかかるので、アプリごとに覚えておく) */
    private val appNames = HashMap<String, String>()

    private fun appName(pkg: String): String = appNames[pkg] ?: run {
        val pm = app?.packageManager ?: return pkg
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?.also { appNames[pkg] = it } ?: pkg
    }

    // ---- 操作 ----

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun next() { controller?.transportControls?.skipToNext() }

    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms.coerceAtLeast(0)) }

    /** 再生しているアプリを開く */
    fun openApp(ctx: Context) {
        val pkg = now?.packageName ?: return
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Android's special-access screen; the user must switch this listener on there. */
    fun permissionSettingsIntents(ctx: Context): List<android.content.Intent> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            add(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component(ctx).flattenToString(),
                    ),
            )
        }
        add(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

/** ほかのアプリが示したジャケットの URL を、より大きな画像の URL に書き換える */
internal object ArtUrls {
    /** 試す URL の順番: できるだけ大きい画像の URL を先に、だめなら元の URL */
    fun candidates(uri: String): List<String> {
        val out = ArrayList<String>()
        var u = uri
        // Spotify: spotify:image:<id> は画像サーバーの URL にする
        if (u.startsWith("spotify:image:")) u = "https://i.scdn.co/image/" + u.removePrefix("spotify:image:")
        if (u.contains("i.scdn.co/image/")) {
            // 大きさは ID の先頭で決まる(ab67616d00001e02 = 300px、0000b273 = 640px、000082c1 = 最大)
            val m = Regex("""ab67616d0000[0-9a-f]{4}""").find(u)
            if (m != null) {
                out += u.replaceRange(m.range, "ab67616d000082c1")
                out += u.replaceRange(m.range, "ab67616d0000b273")
            }
        }
        // YouTube Music などの Google の画像(…=w120-h120… / …=s120)
        if (u.contains("googleusercontent.com") || u.contains("ggpht.com")) {
            val big = u.replace(Regex("""=w\d+-h\d+"""), "=w1200-h1200").replace(Regex("""=s\d+(?=[-?]|$)"""), "=s1200")
            if (big != u) out += big
        }
        // Apple Music(…/100x100bb.jpg)
        Regex("""/\d+x\d+(bb|cc)?\.(jpg|png|webp)""").find(u)?.let { m ->
            out += u.replaceRange(m.range, "/1200x1200" + m.groupValues[1] + "." + m.groupValues[2])
        }
        // Amazon(…._SL300_.jpg や ._SX300_.jpg)
        Regex("""\._S[LXY]\d+_""").find(u)?.let { m -> out += u.replaceRange(m.range, "._SL1200_") }
        out += u
        return out.distinct()
    }
}

/**
 * 「通知へのアクセス」を許可してもらうためだけのサービス(ほかのアプリのメディアセッションを読むのに要る)。
 * 通知の中身は読まない
 */
class MediaListenerService : android.service.notification.NotificationListenerService()
