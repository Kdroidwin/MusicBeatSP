package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.StreamResolver
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.Locale

/** Reads a remote file in bounded windows rather than as one open-ended request. */
internal class DesktopRangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
) {

    init {
        require(windowBytes in MIN_WINDOW_BYTES..MAX_WINDOW_BYTES) { "Invalid stream window size" }
    }

    /** Total length in bytes, or -1 until the first response has stated one. */
    var length: Long = -1L
        private set

    private var position = 0L
    private var window = ByteArray(0)

    /** Where [window] starts in the file; -1 when nothing is held. */
    private var windowStart = -1L

    /** Where the next read will come from. */
    fun position(): Long = position

    fun seek(offset: Long) {
        position = offset.coerceAtLeast(0)
    }

    /**
     * Fills up to [count] bytes at the current position, returning how many — or -1 at the end of
     * the file.
     */
    fun read(into: ByteArray, count: Int): Int {
        require(count in 0..into.size) { "Read size is outside the destination buffer" }
        if (count <= 0) return 0
        if (length >= 0 && position >= length) return -1
        if (!holds(position) && !fetchWindowFor(position)) return -1
        val offset = (position - windowStart).toInt()
        val available = window.size - offset
        if (available <= 0) return -1
        val taken = minOf(count, available)
        System.arraycopy(window, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun holds(at: Long): Boolean =
        windowStart >= 0 && at >= windowStart && at < windowStart + window.size

    /** Fetches the window containing [at]. */
    private fun fetchWindowFor(at: Long): Boolean {
        val initialUri = parseUri(url) ?: return false
        val start = at - (at % windowBytes)
        if (start > Long.MAX_VALUE - windowBytes) return false
        val end = start + windowBytes - 1
        var uri = initialUri
        var requestHeaders = validatedHeaders(headers) ?: return false
        var redirects = 0
        redirectLoop@ while (true) {
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Range", "bytes=$start-$end")
                // Byte offsets are only meaningful when the server does not compress the response.
                .header("Accept-Encoding", "identity")
            if (requestHeaders.keys.none { it.equals("accept", ignoreCase = true) }) {
                request.header("Accept", "*/*")
            }
            requestHeaders.forEach { (name, value) -> request.header(name, value) }

            val response = runCatching {
                client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            }.getOrNull() ?: return false
            val responseStream = response.body()
            try {
                val location = response.headers().firstValue("location").orElse(null)
                if (response.statusCode() in REDIRECT_CODES && location != null) {
                    if (redirects++ >= MAX_REDIRECTS) return false
                    val next = runCatching { uri.resolve(location) }.getOrNull() ?: return false
                    if (parseUri(next.toString()) == null) return false
                    if (uri.scheme.equals("https", ignoreCase = true) &&
                        next.scheme.equals("http", ignoreCase = true)
                    ) return false
                    if (!sameOrigin(uri, next)) {
                        requestHeaders = requestHeaders.filterKeys {
                            it.lowercase(Locale.ROOT) !in SENSITIVE_HEADERS
                        }
                    }
                    uri = next
                    continue@redirectLoop
                }
                if (response.statusCode() !in 200..299) {
                    // A URL cleared before playback has been refused mid-track: tell the resolver, as the
                    // phone's ChunkedDataSource does, so it forgets it and benches the client that minted it.
                    StreamResolver.onPlaybackRefused(url, response.statusCode())
                    return false
                }

                val statedRange = response.headers().firstValue("content-range").orElse(null)
                val statedLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
                val responseLimit = if (response.statusCode() == 206) windowBytes else MAX_IGNORED_RANGE_BYTES
                if (statedLength > responseLimit) return false
                val body = runCatching { readBounded(responseStream, responseLimit) }.getOrNull() ?: return false
                if (body.isEmpty()) return false

                if (response.statusCode() == 206) {
                    val range = parseContentRange(statedRange) ?: return false
                    if (range.first != start || range.second > end ||
                        body.size.toLong() != range.second - range.first + 1 ||
                        body.size > windowBytes
                    ) return false
                    windowStart = range.first
                    length = range.third
                } else {
                    // Keep compatibility with servers that ignore Range for ordinary-sized audio,
                    // while bounding the maximum response held in memory.
                    windowStart = 0L
                    length = statedLength.takeIf { it >= 0 } ?: body.size.toLong()
                }
                window = body
                return true
            } finally {
                runCatching { responseStream.close() }
            }
        }
    }

    private fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
        val match = value?.let { CONTENT_RANGE.matchEntire(it.trim()) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalText = match.groupValues[3]
        val total = totalText.toLongOrNull() ?: if (totalText == "*") -1L else return null
        return if (start <= end && (total < 0 || end < total)) Triple(start, end, total) else null
    }

    private fun parseUri(value: String): URI? {
        if (value.length !in 1..MAX_URL_CHARS) return null
        val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
        return uri.takeIf {
            it.isAbsolute && it.scheme?.lowercase(Locale.ROOT) in HTTP_SCHEMES &&
                !it.host.isNullOrBlank() && (it.port == -1 || it.port in 1..65_535) &&
                it.userInfo == null && it.fragment == null
        }
    }

    private fun validatedHeaders(values: Map<String, String>): Map<String, String>? {
        if (values.size > MAX_HEADER_COUNT) return null
        val result = linkedMapOf<String, String>()
        val seenNames = mutableSetOf<String>()
        for ((name, value) in values) {
            if (!seenNames.add(name.lowercase(Locale.ROOT))) return null
            if (name.isBlank() || name.length > MAX_HEADER_NAME_CHARS ||
                !name.matches(HEADER_NAME) || value.length > MAX_HEADER_VALUE_CHARS ||
                value.any { it == '\r' || it == '\n' } ||
                name.lowercase(Locale.ROOT) in HOP_BY_HOP_HEADERS ||
                name.lowercase(Locale.ROOT) in RESERVED_HEADERS
            ) return null
            result[name] = value
        }
        return result
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Audio server returned an oversized range response" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private companion object {
        /** Comfortably inside what Google will serve. */
        const val DEFAULT_WINDOW = 512 * 1024
        const val MIN_WINDOW_BYTES = 4 * 1024
        const val MAX_WINDOW_BYTES = 2 * 1024 * 1024
        const val MAX_IGNORED_RANGE_BYTES = 64 * 1024 * 1024
        const val MAX_URL_CHARS = 4_096
        const val MAX_HEADER_COUNT = 40
        const val MAX_HEADER_NAME_CHARS = 128
        const val MAX_HEADER_VALUE_CHARS = 8_192
        const val MAX_REDIRECTS = 5
        val HTTP_SCHEMES = setOf("http", "https")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SENSITIVE_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
        val RESERVED_HEADERS = setOf("range", "accept-encoding")
        val HOP_BY_HOP_HEADERS = setOf(
            "connection", "content-length", "expect", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
        )
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

        val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }
}
