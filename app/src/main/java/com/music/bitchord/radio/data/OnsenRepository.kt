package com.music.bitchord.radio.data

import android.net.Uri
import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.radio.model.OnsenEpisode
import com.music.bitchord.radio.model.OnsenProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

/** Onsen's public program catalog and episode playback URLs. */
object OnsenRepository {
    private const val BASE_URL = "https://www.onsen.ag"
    private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
    private const val PROGRAM_CACHE_MS = 5 * 60 * 1000L

    @Volatile
    private var cachedPrograms: List<OnsenProgram> = emptyList()
    @Volatile
    private var cachedAtMs = 0L

    suspend fun fetchPrograms(forceRefresh: Boolean = false): List<OnsenProgram> = withContext(Dispatchers.IO) {
        ensureNetworkAllowed()
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedPrograms.isNotEmpty() && now - cachedAtMs < PROGRAM_CACHE_MS) {
            return@withContext cachedPrograms
        }
        val root = JSONTokener(getText("$BASE_URL/web_api/programs")).nextValue()
        val programs = programArray(root)
            .mapNotNull(::parseProgram)
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy(OnsenProgram::id)
            .sortedBy(OnsenProgram::title)
        cachedPrograms = programs
        cachedAtMs = now
        programs
    }

    suspend fun fetchProgram(id: String): OnsenProgram = withContext(Dispatchers.IO) {
        ensureNetworkAllowed()
        require(id.isNotBlank()) { "Missing Onsen program id" }
        cachedPrograms.firstOrNull { it.id == id }
            ?: fetchPrograms().firstOrNull { it.id == id }
            ?: throw IOException("Onsen program was not found")
    }

    suspend fun searchPrograms(query: String): List<OnsenProgram> = withContext(Dispatchers.IO) {
        ensureNetworkAllowed()
        val word = query.trim()
        val programs = fetchPrograms()
        if (word.isBlank()) return@withContext programs

        val searchUrl = Uri.parse("$BASE_URL/web_api/programs/search")
            .buildUpon()
            .appendQueryParameter("word", word)
            .build()
            .toString()
        val ids = runCatching {
            parseProgramIds(JSONTokener(getText(searchUrl)).nextValue())
        }.getOrNull()
        if (ids != null) {
            val programsById = programs.associateBy(OnsenProgram::id)
            val matches = ids.mapNotNull(programsById::get).distinctBy(OnsenProgram::id)
            if (matches.isNotEmpty() || ids.isEmpty()) return@withContext matches
        }

        programs.filter { program ->
            program.title.contains(word, ignoreCase = true) ||
                program.performer.orEmpty().contains(word, ignoreCase = true) ||
                program.description.contains(word, ignoreCase = true) ||
                program.directoryName.contains(word, ignoreCase = true)
        }
    }

    private suspend fun getText(url: String): String {
        ensureNetworkAllowed()
        val requestBuilder = Request.Builder().url(url)
        OnsenRequestHeaders.api.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.get().build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for ${request.url.encodedPath}")
                throw IOException("Onsen returned HTTP ${response.code}")
            }
            Http.readBoundedUtf8(response.body, MAX_RESPONSE_BYTES)
                ?: throw IOException("Onsen returned an empty response")
        }
    }

    private fun ensureNetworkAllowed() {
        if (AppSettings.offlineMode.value) throw IOException("Offline mode is enabled")
    }

    private const val TAG = "OnsenRepository"

    private fun programArray(root: Any?): List<JSONObject> {
        val candidate = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                val data = root.opt("data")
                when (data) {
                    is JSONArray -> data
                    is JSONObject -> data.optJSONArray("programs")
                        ?: data.optJSONArray("all")
                        ?: data.optJSONArray("data")
                    else -> null
                } ?: root.optJSONArray("programs")
                    ?: root.optJSONObject("programs")?.optJSONArray("all")
                    ?: root.optJSONObject("programs")?.optJSONObject("programs")?.optJSONArray("all")
                    ?: root.optJSONObject("state")?.optJSONObject("programs")
                        ?.optJSONObject("programs")?.optJSONArray("all")
            }
            else -> null
        }
        return candidate?.objects().orEmpty().filter { item ->
            item.optBoolean("list", true) && item.optBoolean("display", true)
        }
    }

    private fun parseProgramIds(root: Any?): List<String>? {
        val candidate = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("data")
                ?: root.optJSONArray("program_ids")
                ?: root.optJSONObject("data")?.optJSONArray("program_ids")
            else -> null
        } ?: return null
        return List(candidate.length()) { index ->
            when (val value = candidate.opt(index)) {
                is Number -> value.toLong().takeIf { it > 0L }?.toString()
                is String -> value.trim().takeIf(String::isNotBlank)
                else -> null
            }
        }.filterNotNull().distinct()
    }

    private fun parseProgram(row: JSONObject, fallbackId: String? = null): OnsenProgram? {
        val info = row.optJSONObject("program_info")
            ?: row.optJSONObject("programInfo")
            ?: row.optJSONObject("info")
            ?: JSONObject()
        val contents = contentArray(row)
        val id = row.string("id", "program_id", "programId") ?: fallbackId ?: return null
        val title = row.string("title", "name")
            ?: info.string("title", "name")
            ?: row.string("directory_name", "directoryName")
            ?: return null
        val image = row.imageUrl("poster_image_url", "posterImageUrl", "image_url", "imageUrl", "thumbnail_url", "poster_url", "thumbnail", "image")
            ?: info.imageUrl("poster_image_url", "posterImageUrl", "image_url", "imageUrl", "thumbnail_url", "poster_url", "thumbnail", "image")
            ?: contents.firstNotNullOfOrNull { it.imageUrl }
        return OnsenProgram(
            id = id,
            directoryName = row.string("directory_name", "directoryName", "slug") ?: "",
            title = title,
            performer = performers(row),
            description = row.string("description", "text", "comment")
                ?: info.string("description", "text", "comment", "outline")
                ?: "",
            imageUrl = image,
            episodes = contents,
        )
    }

    private fun contentArray(program: JSONObject): List<OnsenEpisode> {
        val contentValue = program.opt("contents") ?: program.opt("episodes") ?: program.opt("content_list")
        val array = when (contentValue) {
            is JSONArray -> contentValue
            is JSONObject -> contentValue.optJSONArray("data") ?: contentValue.optJSONArray("items")
            else -> null
        } ?: return emptyList()
        return array.objects().mapNotNull(::parseEpisode)
    }

    private fun parseEpisode(row: JSONObject): OnsenEpisode? {
        val id = row.string("id", "content_id", "contentId") ?: return null
        val mediaType = row.string("media_type", "mediaType", "type").orEmpty().lowercase()
        val isAudio = !row.optBoolean("movie", false) &&
            !mediaType.contains("video") && !mediaType.contains("movie")
        return OnsenEpisode(
            id = id,
            title = row.string("title", "name") ?: "",
            deliveryDate = row.string("delivery_date", "deliveryDate", "date", "updated"),
            imageUrl = row.imageUrl("poster_image_url", "posterImageUrl", "image_url", "imageUrl", "thumbnail_url", "poster_url", "thumbnail"),
            streamingUrl = row.string("streaming_url", "streamingUrl", "media_url", "mediaUrl")
                ?.let(::absoluteHttpsUrl),
            isPremium = row.optBoolean("premium", false),
            isAudio = isAudio,
        )
    }

    private fun performers(row: JSONObject): String? {
        val source = row.optJSONArray("performers") ?: row.optJSONArray("hosts")
            ?: row.optJSONArray("guests")
        if (source == null) {
            return row.optJSONObject("performer")?.string("name", "title")
                ?: row.string("performer", "performer_name", "host")
        }
        val names = List(source.length()) { index ->
            when (val person = source.opt(index)) {
                is JSONObject -> person.string("name", "title")
                is String -> person.trim().takeIf(String::isNotBlank)
                else -> null
            }
        }.filterNotNull()
        return names.distinct().joinToString("、").takeIf(String::isNotBlank)
    }

    private fun JSONObject.imageUrl(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        val value = opt(key)
        val url = when (value) {
            is String -> value
            is JSONObject -> value.string("url", "src", "image_url")
            else -> null
        }
        url?.trim()?.takeIf(String::isNotBlank)?.let(::absoluteHttpsUrl)
    }

    private fun JSONObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        when (val value = opt(key)) {
            is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
            is Number -> value.toLong().takeIf { it > 0L }?.toString()
            else -> null
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> optJSONObject(index) }
        .filterNotNull()

    private fun absoluteHttpsUrl(value: String): String? = runCatching {
        val raw = value.trim()
        val uri = when {
            raw.startsWith("//") -> Uri.parse("https:$raw")
            raw.startsWith("/") -> Uri.parse("$BASE_URL$raw")
            else -> Uri.parse(raw)
        }
        val absolute = if (uri.isRelative) Uri.parse(BASE_URL).buildUpon().appendEncodedPath(raw).build() else uri
        absolute.takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() }?.toString()
    }.getOrNull()
}
