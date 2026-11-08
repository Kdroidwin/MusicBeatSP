package com.music.bitchord.data.spotify

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Spotify の項目の種類 */
enum class SpKind { TRACK, ALBUM, PLAYLIST, ARTIST }

/** 一覧に出す Spotify の項目(曲・アルバム・プレイリスト・アーティスト) */
data class SpItem(
    val kind: SpKind,
    val id: String,
    val uri: String,
    val title: String,
    val sub: String,
    val image: String?,
    val durationMs: Long = 0,
    /** 曲: アルバムやプレイリストの中での位置(そこから再生するときに使う)。それ以外は -1 */
    val index: Int = -1,
    /** 曲: アルバムと、最初のアーティスト(「アルバムを表示」などに使う) */
    val albumUri: String? = null,
    val albumName: String? = null,
    val artistUri: String? = null,
    val artistName: String? = null,
    /** プレイリスト: 作成者の ID と、共同編集かどうか(曲を追加できるかの判断に使う) */
    val ownerId: String? = null,
    val collaborative: Boolean = false,
)

/** 1 ページ分の結果。next が true なら続きがある。cursor は「次のページ」を位置ではなく目印で指す一覧で使う */
class SpPage(val items: List<SpItem>, val next: Boolean, val cursor: String? = null)

/** 検索の結果(種類ごと) */
class SpSearch(val tracks: List<SpItem>, val albums: List<SpItem>, val artists: List<SpItem>, val playlists: List<SpItem>)

/** 再生待ちの曲(今の曲と、次に再生される曲) */
class SpQueue(val current: SpItem?, val next: List<SpItem>)

/** Spotify Connect の再生先 */
class SpDevice(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val restricted: Boolean,
    val volume: Int?,
    val supportsVolume: Boolean,
)

/** 自分のものでも共同編集者でもないプレイリストは、曲の一覧を取得できない(2026 年 2 月からの Web API の制限) */
class SpotifyForbidden(msg: String) : IOException(msg)

/** 一覧を読む関数(位置と、前のページが返した目印を受け取る) */
typealias SpFetch = (Context, Int, String?) -> SpPage

/**
 * Spotify Web API(閲覧と、ライブラリ・プレイリスト・再生先の操作)。再生そのものは SpotifyRemote で公式アプリに任せる。
 * 通信するので、すべて IO スレッドで呼ぶ
 */
object SpotifyApi {
    private const val BASE = "https://api.spotify.com/v1"
    const val PAGE = 50
    /** 検索は 1 回に 10 件まで(開発モードの制限) */
    const val SEARCH_PAGE = 10

    class Me(val id: String, val name: String)

    fun me(ctx: Context): Me {
        val o = get(ctx, "/me")
        return Me(o.optString("id"), o.optString("display_name").ifEmpty { o.optString("id") })
    }

    // ---------------- 一覧 ----------------

    fun myPlaylists(ctx: Context, offset: Int): SpPage {
        val o = get(ctx, "/me/playlists?limit=$PAGE&offset=$offset")
        return SpPage(o.items().mapNotNull { playlist(it) }, o.hasNext())
    }

    fun savedAlbums(ctx: Context, offset: Int): SpPage {
        val o = get(ctx, "/me/albums?limit=$PAGE&offset=$offset")
        return SpPage(o.items().mapNotNull { album(it.optJSONObject("album")) }, o.hasNext())
    }

    fun savedTracks(ctx: Context, offset: Int): SpPage {
        val o = get(ctx, "/me/tracks?limit=$PAGE&offset=$offset")
        return SpPage(
            o.items().mapIndexedNotNull { i, it -> track(it.optJSONObject("track"), null, offset + i) },
            o.hasNext(),
        )
    }

    fun playlistTracks(ctx: Context, id: String, offset: Int): SpPage {
        val o = get(ctx, "/playlists/${enc(id)}/items?limit=$PAGE&offset=$offset")
        // 位置は消えた曲(null)も数える(プレイリストの中の位置で再生を始めるため)
        val list = o.items().mapIndexedNotNull { i, it ->
            track(it.optJSONObject("item") ?: it.optJSONObject("track"), null, offset + i)
        }
        return SpPage(list, o.hasNext())
    }

    fun albumTracks(ctx: Context, album: SpItem, offset: Int): SpPage {
        val o = get(ctx, "/albums/${enc(album.id)}/tracks?limit=$PAGE&offset=$offset")
        return SpPage(
            o.items().mapIndexedNotNull { i, it ->
                track(it, album.image, offset + i)?.copy(albumUri = album.uri, albumName = album.title)
            },
            o.hasNext(),
        )
    }

    fun album(ctx: Context, id: String): SpItem? = album(get(ctx, "/albums/${enc(id)}"))

    fun artist(ctx: Context, id: String): SpItem? = artist(get(ctx, "/artists/${enc(id)}"))

    fun playlist(ctx: Context, id: String): SpItem? = playlist(get(ctx, "/playlists/${enc(id)}"))

    fun artistAlbums(ctx: Context, id: String, offset: Int): SpPage {
        // このエンドポイントは 1 回に 10 件まで
        val o = get(ctx, "/artists/${enc(id)}/albums?include_groups=album,single,compilation&limit=10&offset=$offset")
        return SpPage(o.items().mapNotNull { album(it) }, o.hasNext())
    }

    /** 最近再生した曲(最大 50 曲。続きは読まない) */
    fun recentlyPlayed(ctx: Context): SpPage {
        val o = get(ctx, "/me/player/recently-played?limit=$PAGE")
        // 同じ曲を続けて何度も聴いたときは、1 つにまとめる
        val seen = HashSet<String>()
        val list = o.items().mapNotNull { track(it.optJSONObject("track"), null, -1) }.filter { seen.add(it.uri) }
        return SpPage(list, false)
    }

    /** よく聴く曲・アーティスト(ここ半年ほど) */
    fun topTracks(ctx: Context, offset: Int): SpPage {
        val o = get(ctx, "/me/top/tracks?time_range=medium_term&limit=$PAGE&offset=$offset")
        return SpPage(o.items().mapNotNull { track(it, null, -1) }, o.hasNext())
    }

    fun topArtists(ctx: Context, offset: Int): SpPage {
        val o = get(ctx, "/me/top/artists?time_range=medium_term&limit=$PAGE&offset=$offset")
        return SpPage(o.items().mapNotNull { artist(it) }, o.hasNext())
    }

    /** フォロー中のアーティスト(続きは位置ではなく、最後のアーティストの ID で指す) */
    fun followedArtists(ctx: Context, cursor: String?): SpPage {
        val after = cursor?.let { "&after=${enc(it)}" }.orEmpty()
        val o = get(ctx, "/me/following?type=artist&limit=$PAGE$after").optJSONObject("artists") ?: JSONObject()
        val next = o.optJSONObject("cursors")?.optString("after")?.takeIf { it.isNotEmpty() && it != "null" }
        return SpPage(o.items().mapNotNull { artist(it) }, o.hasNext() && next != null, next)
    }

    fun search(ctx: Context, q: String): SpSearch {
        val o = get(ctx, "/search?type=track,album,artist,playlist&limit=$SEARCH_PAGE&q=${enc(q)}")
        fun sect(name: String) = o.optJSONObject(name)?.items().orEmpty()
        return SpSearch(
            tracks = sect("tracks").mapNotNull { track(it, null, -1) },
            albums = sect("albums").mapNotNull { album(it) },
            artists = sect("artists").mapNotNull { artist(it) },
            playlists = sect("playlists").mapNotNull { playlist(it) },
        )
    }

    // ---------------- 再生待ち・再生先 ----------------

    fun queue(ctx: Context): SpQueue {
        val o = get(ctx, "/me/player/queue")
        val q = o.optJSONArray("queue")
        val list = if (q == null) emptyList() else (0 until q.length()).mapNotNull { track(q.optJSONObject(it), null, -1) }
        return SpQueue(track(o.optJSONObject("currently_playing"), null, -1), list)
    }

    fun devices(ctx: Context): List<SpDevice> {
        val a = get(ctx, "/me/player/devices").optJSONArray("devices") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { d ->
            val id = d.optString("id").takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            SpDevice(
                id, d.optString("name"), d.optString("type"), d.optBoolean("is_active"), d.optBoolean("is_restricted"),
                if (d.isNull("volume_percent")) null else d.optInt("volume_percent"), d.optBoolean("supports_volume"),
            )
        }
    }

    /**
     * アルバム・プレイリストを、position 番目(0 から。消えた曲も数える)の曲から再生する。
     * 再生先は今使われている機器(この端末の Spotify アプリにつないだ直後なら、この端末)
     */
    fun playFrom(ctx: Context, contextUri: String, position: Int) {
        val body = JSONObject()
            .put("context_uri", contextUri)
            .put("offset", JSONObject().put("position", position))
            .put("position_ms", 0)
        send(ctx, "PUT", "/me/player/play", body.toString())
    }

    /** 再生先を切り替える(切り替えた先で再生を続ける) */
    fun transfer(ctx: Context, deviceId: String) {
        send(ctx, "PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", true).toString())
    }

    fun setVolume(ctx: Context, percent: Int, deviceId: String?) {
        val dev = deviceId?.let { "&device_id=${enc(it)}" }.orEmpty()
        send(ctx, "PUT", "/me/player/volume?volume_percent=${percent.coerceIn(0, 100)}$dev", null)
    }

    // ---------------- ライブラリ(お気に入り・保存・フォロー) ----------------

    /** ライブラリに入っているか(uris と同じ順の true/false)。1 回に 40 件まで */
    fun contains(ctx: Context, uris: List<String>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        val out = ArrayList<Boolean>()
        uris.chunked(40).forEach { part ->
            val a = JSONArray(send(ctx, "GET", "/me/library/contains?uris=${enc(part.joinToString(","))}", null))
            part.indices.forEach { out.add(a.optBoolean(it)) }
        }
        return out
    }

    /** ライブラリに保存する(曲・アルバム・プレイリスト。アーティストは Web API では保存できない) */
    fun save(ctx: Context, uri: String) {
        send(ctx, "PUT", "/me/library?uris=${enc(uri)}", null)
    }

    fun unsave(ctx: Context, uri: String) {
        send(ctx, "DELETE", "/me/library?uris=${enc(uri)}", null)
    }

    // ---------------- プレイリストの編集 ----------------

    fun addToPlaylist(ctx: Context, playlistId: String, uris: List<String>) {
        uris.chunked(100).forEach { part ->
            send(ctx, "POST", "/playlists/${enc(playlistId)}/items", JSONObject().put("uris", JSONArray(part)).toString())
        }
    }

    fun removeFromPlaylist(ctx: Context, playlistId: String, uri: String) {
        val body = JSONObject().put("items", JSONArray().put(JSONObject().put("uri", uri)))
        send(ctx, "DELETE", "/playlists/${enc(playlistId)}/items", body.toString())
    }

    fun createPlaylist(ctx: Context, name: String, public: Boolean): SpItem? {
        val body = JSONObject().put("name", name).put("public", public)
        return playlist(JSONObject(send(ctx, "POST", "/me/playlists", body.toString())))
    }

    // ---------------- JSON から項目へ ----------------

    private fun JSONObject.items(): List<JSONObject> {
        val a = optJSONArray("items") ?: return emptyList()
        // 消えた曲などは null になっていることがあるので、空のオブジェクトにして位置を保つ
        return (0 until a.length()).map { a.optJSONObject(it) ?: JSONObject() }
    }

    private fun JSONObject.hasNext() = !isNull("next") && optString("next").isNotEmpty()

    /** 一覧の大きさに合う画像(300px 前後)を選ぶ */
    private fun image(a: JSONArray?): String? {
        if (a == null || a.length() == 0) return null
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        val good = list.filter { it.optInt("width", 0) >= 200 }.minByOrNull { it.optInt("width") }
        return (good ?: list.first()).optString("url").ifEmpty { null }
    }

    private fun names(a: JSONArray?): String =
        if (a == null) "" else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")

    private fun track(o: JSONObject?, fallbackImage: String?, index: Int): SpItem? {
        if (o == null || o.optString("type", "track") != "track") return null
        val uri = o.optString("uri").ifEmpty { return null }
        // ローカルファイル(spotify:local:…)は公式アプリでしか再生できないので、一覧から外す
        if (uri.startsWith("spotify:local:")) return null
        val album = o.optJSONObject("album")
        val firstArtist = o.optJSONArray("artists")?.optJSONObject(0)
        return SpItem(
            SpKind.TRACK, o.optString("id"), uri, o.optString("name"),
            names(o.optJSONArray("artists")),
            image(album?.optJSONArray("images")) ?: fallbackImage,
            o.optLong("duration_ms"), index,
            albumUri = album?.optString("uri")?.ifEmpty { null },
            albumName = album?.optString("name")?.ifEmpty { null },
            artistUri = firstArtist?.optString("uri")?.ifEmpty { null },
            artistName = firstArtist?.optString("name")?.ifEmpty { null },
        )
    }

    private fun album(o: JSONObject?): SpItem? {
        if (o == null) return null
        val uri = o.optString("uri").ifEmpty { return null }
        val year = o.optString("release_date").take(4)
        val sub = listOf(names(o.optJSONArray("artists")), year).filter { it.isNotEmpty() }.joinToString(" · ")
        val firstArtist = o.optJSONArray("artists")?.optJSONObject(0)
        return SpItem(
            SpKind.ALBUM, o.optString("id"), uri, o.optString("name"), sub, image(o.optJSONArray("images")),
            artistUri = firstArtist?.optString("uri")?.ifEmpty { null },
            artistName = firstArtist?.optString("name")?.ifEmpty { null },
        )
    }

    private fun artist(o: JSONObject?): SpItem? {
        if (o == null) return null
        val uri = o.optString("uri").ifEmpty { return null }
        return SpItem(SpKind.ARTIST, o.optString("id"), uri, o.optString("name"), "", image(o.optJSONArray("images")))
    }

    private fun playlist(o: JSONObject?): SpItem? {
        if (o == null) return null
        val uri = o.optString("uri").ifEmpty { return null }
        val owner = o.optJSONObject("owner")
        val ownerName = owner?.let { it.optString("display_name").ifEmpty { it.optString("id") } }.orEmpty()
        return SpItem(
            SpKind.PLAYLIST, o.optString("id"), uri, o.optString("name"), ownerName, image(o.optJSONArray("images")),
            ownerId = owner?.optString("id"), collaborative = o.optBoolean("collaborative"),
        )
    }

    private fun enc(s: String): String = Uri.encode(s)

    /** spotify:album:xxx から xxx を取り出す */
    fun idOf(uri: String): String = uri.substringAfterLast(':')

    // ---------------- 通信 ----------------

    private fun get(ctx: Context, path: String): JSONObject {
        val body = send(ctx, "GET", path, null)
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    /** Web API を呼び、返ってきた本文(空のこともある)を返す */
    private fun send(ctx: Context, method: String, path: String, json: String?): String {
        var refreshed = false
        for (attempt in 0 until 3) {
            val token = SpotifyAuth.accessToken(ctx, forceRefresh = refreshed)
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            c.connectTimeout = 10000
            c.readTimeout = 20000
            try {
                if (json != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(json.toByteArray()) }
                } else if (method == "PUT" || method == "POST") {
                    // 本文なしの PUT・POST でも、長さ 0 を伝える(伝えないと 411 になるサーバーがある)
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(0)
                }
                val code = c.responseCode
                if (code in 200..299) return runCatching { c.inputStream.use { String(it.readBytes()) } }.getOrDefault("")
                val err = runCatching { c.errorStream?.use { String(it.readBytes()) } }.getOrNull().orEmpty()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
                    .orEmpty().ifEmpty { err.take(200) }
                when {
                    // トークンの期限切れ: 1 回だけ更新してやり直す
                    code == 401 && !refreshed -> { refreshed = true; continue }
                    code == 401 -> { SpotifyAuth.logout(ctx); throw SpotifyLoginRequired() }
                    code == 403 -> throw SpotifyForbidden("HTTP 403 $msg".trim())
                    // 混雑: 指示された時間(長すぎるときは 5 秒)待ってやり直す
                    code == 429 && attempt < 2 -> {
                        val wait = c.getHeaderField("Retry-After")?.toLongOrNull() ?: 1
                        Thread.sleep(wait.coerceIn(1, 5) * 1000)
                        continue
                    }
                    else -> throw IOException("HTTP $code $msg".trim())
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("HTTP 429")
    }

    /** 画像(i.scdn.co など)のバイト列 */
    fun fetchBytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
