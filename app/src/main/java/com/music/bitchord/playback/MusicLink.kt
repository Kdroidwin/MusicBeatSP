package com.music.bitchord.playback

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an intent from outside the app turned out to be asking for. */
sealed interface LinkRequest {
    /** A song, by video id — `watch?v=`, a `youtu.be` short link, a Short. */
    data class Track(val videoId: String) : LinkRequest

    /** An album, playlist or artist page, by browse id. */
    data class Page(val browseId: String) : LinkRequest

    /**
     * Words rather than an id: "play Blinding Lights", or a shared search URL.
     *
     * [play] separates the two. A spoken request is an instruction — it should
     * start the best match, not leave a list on screen for someone whose phone
     * is in their pocket — while a search *link* is a page somebody meant to
     * show you.
     */
    data class Search(val query: String, val play: Boolean) : LinkRequest

    /** An audio file handed to the app by a file manager or document provider. */
    data class ExternalAudio(val uri: String) : LinkRequest

    /** A ZIP archive handed to the app by a file manager such as MiXplorer. */
    data class ExternalZip(val uri: String) : LinkRequest

    /** "Play music", with nothing said about what. */
    data object Resume : LinkRequest
}

/**
 * Links and voice requests handed to BitChord from elsewhere on the device.
 *
 * The same relay [PlayerDeepLink] is, and for the same reason: what has to
 * happen — start a queue, push a page, run a search — is all inside
 * `BitChordApp`'s composition, which [MainActivity][com.music.bitchord.MainActivity]
 * has no handle on. The activity reads the intent, this holds the answer, and
 * the composition serves it once its controller and view model exist.
 *
 * Kept apart from [PlayerDeepLink] rather than folded into it because the two
 * are answered by different things at different times: opening the player is a
 * boolean the sheet reads, while this is a request that may take a network
 * round trip before anything happens on screen.
 */
object MusicLink {

    /**
     * Marks an intent as already read.
     *
     * MainActivity is `singleTask` and declares no `configChanges`, so a theme
     * or font-size change destroys and recreates it — with `getIntent()` still
     * returning the link that launched it. Without this, rotating the phone an
     * hour later would replay the shared song over whatever is playing.
     */
    private const val EXTRA_CONSUMED = "bitchord.linkConsumed"

    private val _pending = MutableStateFlow<LinkRequest?>(null)

    /** The outstanding request, or null. Cleared by [handled]. */
    val pending: StateFlow<LinkRequest?> = _pending.asStateFlow()

    /** Reads an incoming intent, and reports whether it carried a request. */
    fun consume(intent: Intent?): Boolean {
        if (intent == null) return false
        // This Activity has to be exported for file managers, share sheets,
        // widgets and YouTube links. Treat every parcel supplied by another
        // app as hostile: malformed Parcelable extras must not crash launch.
        val alreadyConsumed = runCatching { intent.getBooleanExtra(EXTRA_CONSUMED, false) }.getOrDefault(true)
        if (alreadyConsumed) return false
        val request = runCatching {
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    val uri = intent.data
                    when {
                        uri != null && isSafeLocalUri(uri) && isZipArchiveIntent(intent.type, uri) ->
                            LinkRequest.ExternalZip(uri.toString())
                        uri != null && isSafeLocalUri(uri) && isAudioFileIntent(intent.type, uri) ->
                            LinkRequest.ExternalAudio(uri.toString())
                        else -> uri?.let(::parse)
                    }
                }
                Intent.ACTION_SEND -> {
                    val mimeType = normalizedMimeType(intent.type)
                    val sharedUri = sharedUri(intent)
                    if (sharedUri != null && isSafeLocalUri(sharedUri) && isZipArchiveIntent(mimeType, sharedUri)) {
                        LinkRequest.ExternalZip(sharedUri.toString())
                    } else if (mimeType == "text/plain") {
                        intent.getStringExtra(Intent.EXTRA_TEXT)
                            ?.takeIf { it.length <= MAX_SHARED_TEXT_CHARS }
                            ?.let(::firstUrl)
                            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            ?.let(::parse)
                    } else {
                        null
                    }
                }
                // The assistant's "play <something>". An empty query is the
                // whole point of Resume: carry on with the current queue.
                MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                    val rawQuery = intent.getStringExtra(SearchManager.QUERY).orEmpty()
                    if (rawQuery.length > MAX_SEARCH_QUERY_LENGTH) null else {
                        val query = rawQuery.filterNot { it.isISOControl() }.trim()
                        if (query.isEmpty()) LinkRequest.Resume else LinkRequest.Search(query, play = true)
                    }
                }
                else -> null
            }
        }.getOrNull() ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = request
        return true
    }

    /**
     * Called once the request has actually been acted on.
     *
     * By whoever acted, not by whoever set it — this object outlives the
     * composition, and a request left standing would be served again by the
     * next one, which is the shared song starting over on the first
     * configuration change.
     */
    fun handled() {
        _pending.value = null
    }

    /** Keep SAF grants across process death when the sender offered persistence. */
    fun retainPersistableAudioPermission(context: Context, intent: Intent?) {
        intent ?: return
        @Suppress("DEPRECATION")
        val uri = runCatching {
            intent.data ?: sharedUri(intent)
        }.getOrNull() ?: return
        if (!isSafeLocalUri(uri)) return
        val mimeType = normalizedMimeType(intent.type)
        val supported = isAudioFileIntent(mimeType, uri) || isZipArchiveIntent(mimeType, uri)
        if (!supported || uri.scheme != "content") return
        val persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val readable = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        if (persistable && readable) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * What a YouTube or YouTube Music URL points at, or null for one this app
     * has nothing to show for.
     *
     * Deliberately forgiving about the host: `music.youtube.com`,
     * `www.youtube.com`, `m.youtube.com` and `youtu.be` all address the same
     * catalogue with the same ids, and a link is just as likely to arrive
     * through a share sheet — where the manifest's host list never applies —
     * as through the browser.
     */
    fun parse(uri: Uri): LinkRequest? {
        // MainActivity is exported for Android's link and file-manager flows.
        // Only interpret real web links here; a custom scheme with a forged
        // YouTube-looking host must not be treated as a playback command.
        if (uri.toString().length > MAX_LINK_LENGTH || uri.userInfo != null ||
            uri.scheme?.lowercase() !in setOf("http", "https")
        ) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = uri.pathSegments.orEmpty()
        if (host == "youtu.be") {
            return segments.firstOrNull()?.let(::track)
        }
        if (host != "youtube.com" && host != "music.youtube.com" && host != "m.youtube.com") {
            return null
        }
        val list = uri.getQueryParameter("list")?.trim().orEmpty()
        return when (segments.firstOrNull()) {
            // A watch link often carries the playlist it was opened from as
            // well. The video is what was tapped, so it wins; the list only
            // stands in when the link names no video at all, which is how YT
            // Music writes "play this album" links.
            "watch" -> uri.getQueryParameter("v")?.let(::track) ?: playlist(list)
            "playlist" -> playlist(list)
            // A Short and an embed are both a bare id in the path.
            "shorts", "embed", "v" -> segments.getOrNull(1)?.let(::track)
            // `channel/UC…` is an artist; `browse/MPREb…` is a release. Both
            // are browse ids already, so neither needs rewriting.
            "channel", "browse" -> segments.getOrNull(1)?.takeIf(::isSafeRemoteId)
                ?.let(LinkRequest::Page)
            "search" -> uri.getQueryParameter("q")?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_SEARCH_QUERY_LENGTH }
                ?.let { LinkRequest.Search(it, play = false) }
            // A link to nothing in particular — music.youtube.com itself, an
            // account page. Opening the app on its own tab is the right answer,
            // and that has already happened by the time this is read.
            else -> list.takeIf { it.isNotEmpty() }?.let { playlist(it) }
        }
    }

    private fun isAudioFileIntent(mimeType: String?, uri: Uri): Boolean {
        if (mimeType?.substringBefore(';')?.trim()?.lowercase()?.startsWith("audio/") == true) {
            return true
        }
        val name = uri.lastPathSegment.orEmpty().substringBefore('?').lowercase()
        return AUDIO_EXTENSIONS.any(name::endsWith)
    }

    private fun isZipArchiveIntent(mimeType: String?, uri: Uri): Boolean {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (type in ZIP_MIME_TYPES) return true
        return uri.lastPathSegment.orEmpty().substringBefore('?').endsWith(".zip", ignoreCase = true)
    }

    private fun track(videoId: String): LinkRequest.Track? =
        videoId.trim().takeIf(::isSafeRemoteId)?.let(LinkRequest::Track)

    private fun isSafeRemoteId(value: String): Boolean =
        value.length in 1..MAX_REMOTE_ID_LENGTH && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun sharedUri(intent: Intent): Uri? = runCatching {
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }.getOrNull()

    private fun normalizedMimeType(mimeType: String?): String? = mimeType
        ?.takeIf { it.length <= MAX_MIME_TYPE_CHARS }
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()

    private fun isSafeLocalUri(uri: Uri): Boolean {
        if (uri.toString().length > MAX_LOCAL_URI_LENGTH) return false
        return when (uri.scheme?.lowercase()) {
            "content" -> !uri.authority.isNullOrBlank() && uri.authority!!.length <= MAX_AUTHORITY_CHARS
            "file" -> uri.path?.startsWith('/') == true && uri.authority.isNullOrEmpty()
            else -> false
        }
    }

    /**
     * A playlist id as the browse id its page is fetched under.
     *
     * `VL` is the prefix every playlist browse carries — an album's
     * `OLAK5uy_…` share id included, which is the shape a "share this album"
     * link out of YT Music actually has.
     */
    private fun playlist(listId: String): LinkRequest.Page? {
        if (!isSafeRemoteId(listId)) return null
        val browseId = if (listId.startsWith("VL")) listId else "VL$listId"
        return browseId.takeIf(::isSafeRemoteId)?.let(LinkRequest::Page)
    }

    private const val MAX_LINK_LENGTH = 8_192
    private const val MAX_LOCAL_URI_LENGTH = 8_192
    private const val MAX_AUTHORITY_CHARS = 255
    private const val MAX_MIME_TYPE_CHARS = 128
    private const val MAX_SHARED_TEXT_CHARS = 16 * 1024
    private const val MAX_REMOTE_ID_LENGTH = 256
    private const val MAX_SEARCH_QUERY_LENGTH = 500

    private val AUDIO_EXTENSIONS = setOf(
        ".mp3", ".m4a", ".flac", ".ogg", ".opus", ".aac", ".webm",
        ".wav", ".aiff", ".aif", ".alac", ".ape", ".wma", ".wv", ".3gp",
    )

    private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")

    /**
     * The first http(s) URL in shared text.
     *
     * Share sheets rarely send the bare link: YT Music sends the song's title
     * and a newline before it, other apps wrap it in a sentence.
     */
    private fun firstUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value

    private val URL_IN_TEXT = Regex("""https?://\S+""")
}
