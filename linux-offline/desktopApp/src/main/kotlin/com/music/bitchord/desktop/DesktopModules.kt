package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class DesktopSpineModule(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("type") val type: String = "MODULE",
    @SerialName("description") val description: String = "",
    @SerialName("tags") private val declaredTags: List<String> = emptyList(),
    @SerialName("labels") private val declaredLabels: List<String> = emptyList(),
    @SerialName("download") val download: String = "",
    @SerialName("trusted") val trusted: Boolean = false,
) {
    val tags: List<String> get() = if (declaredTags.isNotEmpty()) declaredTags else declaredLabels
}

@Serializable
private data class DesktopModuleSearchResponse(
    @SerialName("tracks") val tracks: List<DesktopModuleTrack> = emptyList(),
    @SerialName("total") val total: Int = 0,
)

@Serializable
private data class DesktopModuleTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("artistId") val artistId: String? = null,
    @SerialName("album") val album: String = "",
    @SerialName("albumId") val albumId: String? = null,
    @SerialName("albumCover") val albumCover: String? = null,
    @SerialName("duration") val duration: Int = 0,
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("format") val format: String = "",
    @SerialName("availableQualities") val availableQualities: List<String> = emptyList(),
)

@Serializable
private data class DesktopModuleStreamResponse(
    @SerialName("streamUrl") val streamUrl: String? = null,
    @SerialName("track") val track: DesktopModuleStreamTrack? = null,
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
)

@Serializable
private data class DesktopModuleStreamTrack(
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("bitDepth") val bitDepth: Int? = null,
    @SerialName("sampleRate") val sampleRate: Double? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
)

private data class DesktopHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

/** A JSON object that must enter Graal as a guest value, not a host object. */
private data class DesktopJsJson(val value: String)

/** Minimal blocking HTTP bridge used only from the restricted module context. */
private object DesktopModuleHttp {
    private const val MAX_URL_CHARS = 4_096
    private const val MAX_HEADER_COUNT = 40
    private const val MAX_HEADER_NAME_CHARS = 128
    private const val MAX_HEADER_VALUE_CHARS = 8_192
    private const val MAX_REQUEST_BODY_BYTES = 1024 * 1024
    private const val MAX_RESPONSE_BODY_BYTES = 4 * 1024 * 1024
    private const val MAX_RESPONSE_HEADERS = 64
    private const val MAX_REDIRECTS = 5
    private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD")
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val HOP_BY_HOP_HEADERS = setOf(
        "connection", "content-length", "expect", "host", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
    )
    private val RESERVED_STREAM_HEADERS = setOf("range", "accept-encoding")
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        // Redirects are checked manually to prevent a module forwarding request
        // credentials to another host or downgrading HTTPS to cleartext.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun get(url: String, maxResponseBytes: Int = MAX_RESPONSE_BODY_BYTES): DesktopHttpResponse =
        request(url, "GET", emptyMap(), null, maxResponseBytes)

    fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        maxResponseBytes: Int = MAX_RESPONSE_BODY_BYTES,
    ): DesktopHttpResponse {
        require(url.length in 1..MAX_URL_CHARS) { "Module request URL is invalid" }
        require(headers.size <= MAX_HEADER_COUNT) { "Too many module request headers" }
        require(maxResponseBytes in 1..MAX_RESPONSE_BODY_BYTES) { "Module response limit is invalid" }
        val initialUri = parseHttpUri(url)
        val seenHeaderNames = mutableSetOf<String>()
        val safeHeaders = headers.map { (key, value) ->
            require(seenHeaderNames.add(key.lowercase(Locale.ROOT))) { "Duplicate module request header" }
            require(key.isNotBlank() && key.length <= MAX_HEADER_NAME_CHARS &&
                key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Module request header is invalid" }
            require(value.length <= MAX_HEADER_VALUE_CHARS && value.none { it == '\r' || it == '\n' }) {
                "Module request header is invalid"
            }
            require(key.lowercase(Locale.ROOT) !in HOP_BY_HOP_HEADERS) { "Module request header is not allowed" }
            key to value
        }
        val safeMethod = method.uppercase(Locale.ROOT)
        require(safeMethod in ALLOWED_METHODS) { "Unsupported module request method" }
        val requestBody = body?.toByteArray(StandardCharsets.UTF_8)
        require((requestBody?.size ?: 0) <= MAX_REQUEST_BODY_BYTES) { "Module request body is too large" }
        require((safeMethod != "POST" && safeMethod != "PUT") || requestBody != null) {
            "Module request body is required for this method"
        }
        require((safeMethod != "GET" && safeMethod != "HEAD") || requestBody == null) {
            "GET and HEAD requests cannot include a body"
        }

        var uri = initialUri
        var redirects = 0
        while (true) {
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
            val supplied = safeHeaders.keys.any { it.equals("user-agent", ignoreCase = true) }
            if (!supplied) builder.header("User-Agent", USER_AGENT)
            safeHeaders.forEach { (key, value) -> builder.header(key, value) }
            val publisher = if (requestBody == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofByteArray(requestBody)
            }
            when (safeMethod) {
                "POST" -> builder.POST(publisher)
                "PUT" -> builder.PUT(publisher)
                "DELETE" -> if (requestBody == null) builder.DELETE() else builder.method("DELETE", publisher)
                "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                "GET" -> builder.GET()
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { stream ->
                val location = response.headers().firstValue("location").orElse(null)
                if (response.statusCode() in REDIRECT_CODES && location != null) {
                    require(safeMethod == "GET" || safeMethod == "HEAD") {
                        "Only read-only module requests may follow redirects"
                    }
                    require(redirects++ < MAX_REDIRECTS) { "Too many module redirects" }
                    val next = uri.resolve(location)
                    require(isHttpUri(next)) { "Module redirect URL is invalid" }
                    require(!initialUri.scheme.equals("https", ignoreCase = true) ||
                        next.scheme.equals("https", ignoreCase = true)
                    ) {
                        "HTTPS module request cannot redirect to HTTP"
                    }
                    require(sameOrigin(initialUri, next)) {
                        "Cross-origin module redirect is not allowed"
                    }
                    uri = next
                } else {
                    val responseBody = readBounded(stream, maxResponseBytes)
                    val responseHeaders = response.headers().map().entries.take(MAX_RESPONSE_HEADERS)
                        .associate { (key, values) ->
                            key.lowercase(Locale.ROOT).take(MAX_HEADER_NAME_CHARS) to
                                values.joinToString(",").take(MAX_HEADER_VALUE_CHARS)
                        }
                    return DesktopHttpResponse(response.statusCode(), responseBody, responseHeaders)
                }
            }
        }
    }

    private fun parseHttpUri(value: String): URI {
        val uri = try { URI(value) } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Module request URL is invalid")
        }
        require(isHttpUri(uri)) { "Module request must use HTTP or HTTPS" }
        return uri
    }

    fun isHttpUri(uri: URI): Boolean =
        uri.isAbsolute && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65_535) &&
            uri.userInfo == null && uri.fragment == null && uri.toString().length <= MAX_URL_CHARS

    fun validateStreamHeaders(headers: Map<String, String>): Map<String, String> {
        require(headers.size <= MAX_HEADER_COUNT) { "Too many module stream headers" }
        val seenHeaderNames = mutableSetOf<String>()
        return headers.map { (key, value) ->
            require(seenHeaderNames.add(key.lowercase(Locale.ROOT))) { "Duplicate module stream header" }
            require(key.isNotBlank() && key.length <= MAX_HEADER_NAME_CHARS &&
                key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Module stream header is invalid" }
            require(value.length <= MAX_HEADER_VALUE_CHARS && value.none { it == '\r' || it == '\n' }) {
                "Module stream header is invalid"
            }
            require(key.lowercase(Locale.ROOT) !in HOP_BY_HOP_HEADERS &&
                key.lowercase(Locale.ROOT) !in RESERVED_STREAM_HEADERS
            ) { "Module stream header is not allowed" }
            key to value
        }.toMap()
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) && effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream(minOf(limit, 8_192))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Module response body is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}

/** JVM host for the same module shape used by Android's Spine/Ricky sources. */
private class DesktopModuleEngine(
    private val jsCode: String,
    private val baseUrl: String,
) : AutoCloseable {
    private val context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowIO(false)
        .allowNativeAccess(false)
        .allowCreateThread(false)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(MAX_JS_STATEMENTS, null).build())
        .build()

    init {
        require(jsCode.isNotBlank() && jsCode.toByteArray(StandardCharsets.UTF_8).size <= MAX_MODULE_SCRIPT_BYTES) {
            "Module script is empty or too large"
        }
        require(DesktopModuleHttp.isHttpUri(URI.create(baseUrl))) { "Module base URL must use HTTP or HTTPS" }
        val bindings = context.getBindings("js")
        bindings.putMember("__spine_fetch", ProxyExecutable { args ->
            val url = args.getOrNull(0)?.asString() ?: error("fetch requires a URL")
            val method = args.getOrNull(1)?.asString() ?: "GET"
            val headers = args.getOrNull(2)?.asString()?.let(::parseHeaders).orEmpty()
            val body = args.getOrNull(3)?.asString()
            val response = DesktopModuleHttp.request(resolveUrl(url), method, headers, body)
            buildJsonObject {
                put("status", response.status)
                put("ok", response.status in 200..299)
                put("body", response.body)
                put("headers", buildJsonObject {
                    response.headers.forEach { (key, value) -> put(key, value) }
                })
            }.toString()
        })
        bindings.putMember("__spine_log", ProxyExecutable { args ->
            // Module console output is intentionally ignored.
            null
        })

        context.eval("js", POLYFILLS)
        val cleanCode = preprocessModuleCode(jsCode)
        context.eval(
            "js",
            """
            var __spine_iife_error = null;
            globalThis.__spine_mod = (function() {
                try {
                    var module = { exports: {} };
                    var exports = module.exports;
                    var self = {};
                    $cleanCode
                    if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                        return module.exports;
                    }
                    var named = {};
                    if (typeof searchTracks === 'function') named.searchTracks = searchTracks;
                    if (typeof getTrackStreamUrl === 'function') named.getTrackStreamUrl = getTrackStreamUrl;
                    return named;
                } catch (e) {
                    __spine_iife_error = e && e.message ? e.message : String(e);
                    return {};
                }
            })();
            """.trimIndent(),
        )
        val error = context.eval("js", "String(__spine_iife_error || '')").asString()
        check(error.isBlank()) { "Module init error: $error" }
        val exports = context.eval("js", "Object.keys(__spine_mod).join(', ')").asString()
        check("searchTracks" in exports || "getTrackStreamUrl" in exports) {
            "Module has no supported exports"
        }
    }

    fun call(functionName: String, args: List<Any?>): String {
        check(!context.isClosed) { "Module execution context is closed" }
        context.resetLimits()
        require(functionName in SUPPORTED_EXPORTS) { "Unsupported module function" }
        require(args.size <= MAX_CALL_ARGUMENTS) { "Too many module arguments" }
        require(args.all { arg ->
            when (arg) {
                null, is Boolean, is Number -> true
                is String -> arg.length <= MAX_CALL_ARGUMENT_CHARS
                is DesktopJsJson -> arg.value.length <= MAX_CALL_ARGUMENT_CHARS
                else -> false
            }
        }) { "Module arguments are invalid or too large" }
        val function = context.getBindings("js").getMember("__spine_mod")
            ?.getMember(functionName)
        check(function != null && function.canExecute()) {
            "$functionName is not a function"
        }
        val guestArgs = args.map { argument ->
            if (argument is DesktopJsJson) context.eval("js", "(${argument.value})") else argument
        }
        val result = function.execute(*guestArgs.toTypedArray())
        val bindings = context.getBindings("js")
        bindings.putMember("__spine_call_result", result)
        context.eval(
            "js",
            """
            globalThis.__spine_call_done = false;
            globalThis.__spine_call_output = '';
            globalThis.__spine_call_error = '';
            Promise.resolve(globalThis.__spine_call_result).then(
                function(value) {
                    globalThis.__spine_call_output = value === undefined
                        ? 'null'
                        : (typeof value === 'string' ? value : JSON.stringify(value));
                    globalThis.__spine_call_done = true;
                },
                function(error) {
                    globalThis.__spine_call_error = error && error.message
                        ? error.message : String(error);
                    globalThis.__spine_call_done = true;
                }
            );
            """.trimIndent(),
        )
        val deadline = System.nanoTime() + CALL_TIMEOUT_NS
        while (!context.eval("js", "Boolean(__spine_call_done)").asBoolean()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $functionName" }
            if (Thread.interrupted()) throw InterruptedException("Module call interrupted")
            // Evaluating a no-op gives Graal's promise queue a chance to run.
            context.eval("js", "void 0")
        }
        val error = bindings.getMember("__spine_call_error").asString()
        check(error.isBlank()) { error }
        return bindings.getMember("__spine_call_output").asString().also {
            require(it.length <= MAX_MODULE_RESULT_CHARS) { "Module result is too large" }
        }
    }

    override fun close() {
        context.close(true)
    }

    fun isClosed(): Boolean = context.isClosed

    private fun resolveUrl(url: String): String {
        require(url.length in 1..MAX_URL_CHARS) { "Module fetch URL is invalid" }
        val candidate = URI.create(url)
        val resolved = if (candidate.isAbsolute) candidate
        else URI.create(baseUrl.trimEnd('/') + "/").resolve(candidate)
        require(DesktopModuleHttp.isHttpUri(resolved)) { "Module fetch URL must use HTTP or HTTPS" }
        return resolved.toString()
    }

    private fun parseHeaders(raw: String): Map<String, String> = runCatching {
        val objectValue = Json.parseToJsonElement(raw).jsonObject
        objectValue.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private companion object {
        const val CALL_TIMEOUT_NS = 45_000_000_000L
        const val MAX_URL_CHARS = 4_096
        const val MAX_MODULE_SCRIPT_BYTES = 2 * 1024 * 1024
        const val MAX_CALL_ARGUMENTS = 8
        const val MAX_CALL_ARGUMENT_CHARS = 64 * 1024
        const val MAX_MODULE_RESULT_CHARS = 4 * 1024 * 1024
        const val MAX_JS_STATEMENTS = 1_000_000L
        val SUPPORTED_EXPORTS = setOf("searchTracks", "getTrackStreamUrl")

        fun preprocessModuleCode(jsCode: String): String {
            val code = jsCode.trim()
            val template = Regex("""^export\s+const\s+\w+\s*=\s*`""").find(code)
            if (template != null) {
                val start = template.range.last + 1
                var index = start
                while (index < code.length) {
                    if (code[index] == '\\' && index + 1 < code.length) index += 2
                    else if (code[index] == '`') return code.substring(start, index).trim()
                    else index++
                }
            }
            return code
                .replace(Regex("""\bexport\s+default\s+(?=function|class|const|let|var|async)"""), "")
                .replace(Regex("""\bexport\s+(const|let|var|function|class|async)\b"""), "$1")
                .replace(Regex("""\bexport\s*\{[^}]*\}\s*;?"""), "")
        }

        val POLYFILLS = """
            if (typeof globalThis.console === 'undefined') {
                globalThis.console = {
                    log: function() { __spine_log.apply(null, arguments); },
                    warn: function() { __spine_log.apply(null, arguments); },
                    error: function() { __spine_log.apply(null, arguments); },
                    info: function() { __spine_log.apply(null, arguments); }
                };
            }
            if (typeof AbortController === 'undefined') {
                var AbortController = function() { this.signal = { aborted: false }; };
                AbortController.prototype.abort = function() { this.signal.aborted = true; };
            }
            if (typeof atob === 'undefined') {
                var atob = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input);
                    var output = '';
                    for (var bc = 0, bs, buffer, index = 0; buffer = str.charAt(index++); ) {
                        buffer = chars.indexOf(buffer);
                        if (buffer < 0) continue;
                        bs = bc % 4 ? bs * 64 + buffer : buffer;
                        bc++;
                        if (bc % 4) output += String.fromCharCode(255 & bs >> (-2 * bc & 6));
                    }
                    return output;
                };
            }
            var fetch = function(url, options) {
                var method = 'GET';
                var headers = '{}';
                var body = null;
                if (options) {
                    method = options.method || 'GET';
                    if (options.headers) headers = JSON.stringify(options.headers);
                    if (options.body !== undefined && options.body !== null) {
                        body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                    }
                    if (options.signal && options.signal.aborted) throw new Error('Aborted');
                }
                var raw = JSON.parse(__spine_fetch(String(url), method, headers, body));
                var responseBody = raw.body;
                return {
                    ok: raw.ok,
                    status: raw.status,
                    statusText: raw.ok ? 'OK' : 'Error',
                    json: function() { return JSON.parse(responseBody); },
                    text: function() { return responseBody; },
                    arrayBuffer: function() { throw new Error('arrayBuffer is not available in desktop modules'); },
                    clone: function() { return this; },
                    then: function(resolve, reject) {
                        try {
                            var response = this;
                            response.then = undefined;
                            return Promise.resolve(resolve(response));
                        }
                        catch (error) { return Promise.reject(error); }
                    },
                    headers: { get: function(key) { return raw.headers[String(key).toLowerCase()] || null; } }
                };
            };
            if (typeof setTimeout === 'undefined') {
                var setTimeout = function(fn) { if (typeof fn === 'function') fn(); return 0; };
                var clearTimeout = function() {};
            }
        """.trimIndent()
    }
}

private class DesktopModuleManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val engines = ConcurrentHashMap<String, DesktopModuleEngine>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var cachedIndex: Pair<String, List<DesktopSpineModule>>? = null

    suspend fun modules(indexUrl: String): Result<List<DesktopSpineModule>> = withContext(Dispatchers.IO) {
        runCatching {
            cachedIndex?.takeIf { it.first == indexUrl }?.second?.let { return@runCatching it }
            val response = DesktopModuleHttp.get(indexUrl, MAX_MODULE_INDEX_BYTES)
            check(response.status in 200..299) { "Module index returned HTTP ${response.status}" }
            val modules = parseIndex(response.body)
            cachedIndex = indexUrl to modules
            modules
        }
    }

    /** The modules an index document lists. */
    internal fun parseIndex(body: String): List<DesktopSpineModule> {
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_MODULE_INDEX_BYTES) {
            "Module index is too large"
        }
        val root = json.decodeFromString(JsonObject.serializer(), body)
        return root.entries
            .filter { it.key.startsWith("category:") && it.key !in EXCLUDED_CATEGORIES }
            .flatMap { (_, value) ->
                runCatching {
                    json.decodeFromJsonElement<List<DesktopSpineModule>>(value)
                }.getOrDefault(emptyList())
            }
            .filter { it.id.isNotBlank() && it.download.isNotBlank() }
            .distinctBy(DesktopSpineModule::id)
            .take(MAX_MODULE_COUNT)
    }

    suspend fun load(module: DesktopSpineModule, indexUrl: String): Result<DesktopModuleEngine> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = moduleKey(indexUrl, module.id)
                engines[key]?.let { return@runCatching it }
                val downloadUrl = URI.create(indexUrl).resolve(module.download).toString()
                val response = DesktopModuleHttp.get(downloadUrl, MAX_MODULE_SCRIPT_BYTES)
                check(response.status in 200..299) { "Module ${module.id} returned HTTP ${response.status}" }
                val engine = DesktopModuleEngine(response.body, URI.create(downloadUrl).resolve(".").toString())
                engines[key] = engine
                locks.putIfAbsent(key, Mutex())
                engine
            }
        }

    suspend fun call(
        module: DesktopSpineModule,
        indexUrl: String,
        functionName: String,
        args: List<Any?>,
    ): Result<String> {
        val engine = load(module, indexUrl).getOrElse { return Result.failure(it) }
        val lock = locks.getOrPut(moduleKey(indexUrl, module.id)) { Mutex() }
        return runCatching { lock.withLock { engine.call(functionName, args) } }
            .onFailure {
                if (engine.isClosed()) {
                    engines.remove(moduleKey(indexUrl, module.id), engine)
                    locks.remove(moduleKey(indexUrl, module.id), lock)
                }
            }
    }

    fun close() {
        engines.values.forEach { runCatching { it.close() } }
        engines.clear()
        locks.clear()
        cachedIndex = null
    }

    private companion object {
        const val MAX_MODULE_INDEX_BYTES = 2 * 1024 * 1024
        const val MAX_MODULE_SCRIPT_BYTES = 2 * 1024 * 1024
        const val MAX_MODULE_COUNT = 200
        val EXCLUDED_CATEGORIES = setOf("category:artworks", "category:testing")

        fun moduleKey(indexUrl: String, moduleId: String): String = "$indexUrl::$moduleId"
    }
}

/** Desktop source adapter for the Android-compatible module-index protocol. */
internal object DesktopModuleSource {
    private const val TRACK_PREFIX = "module:"
    private const val SEPARATOR = "::"
    internal const val LOSSLESS = "LOSSLESS"
    internal const val DOLBY = "DOLBY"
    private const val HIGH = "HIGH"
    private const val LOW = "LOW"
    private val manager = DesktopModuleManager()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    internal data class ModuleTrackRef(
        val sourceId: String?,
        val moduleId: String,
        val trackId: String,
    )

    fun configured(config: DesktopSourceConfig): Boolean =
        config.kind.needsServer && config.baseUrl.isNotBlank()

    /**
     * The modules an index document lists, read without fetching it again — the sources editor has
     * the body in hand when it identifies a pasted URL.
     */
    internal fun parseIndex(body: String): List<DesktopSpineModule> = manager.parseIndex(body)

    suspend fun health(config: DesktopSourceConfig): Result<String> = manager.modules(config.baseUrl.trim()).mapCatching { modules ->
        check(modules.isNotEmpty()) { "The index listed no modules" }
        "${modules.size} module${if (modules.size == 1) "" else "s"} available"
    }

    suspend fun search(config: DesktopSourceConfig, query: String, limit: Int): Result<List<SearchResult>> {
        if (!configured(config)) return Result.success(emptyList())
        val indexUrl = config.baseUrl.trim()
        return manager.modules(indexUrl).mapCatching { modules ->
            val perModule = coroutineScope {
                modules.map { module ->
                    async { searchModule(module, config.id, indexUrl, query, limit) }
                }.awaitAll()
            }
            interleave(perModule).take(limit)
        }
    }

    suspend fun stream(
        config: DesktopSourceConfig,
        song: Song,
        qualityOverride: String? = null,
    ): Result<DesktopStream?> {
        val reference = parseTrack(song.videoId)
            ?: return Result.failure(IllegalArgumentException("Malformed module track id"))
        if (reference.sourceId != null && reference.sourceId != config.id) {
            return Result.failure(IllegalArgumentException("Track belongs to another module source"))
        }
        val indexUrl = config.baseUrl.trim().takeIf(String::isNotBlank)
            ?: return Result.failure(IllegalStateException("No module index is configured"))
        val quality = (qualityOverride ?: DesktopPersistence().audioQuality().name)
            .uppercase(Locale.ROOT).let {
            when (it) {
                LOSSLESS -> LOSSLESS
                HIGH -> HIGH
                // Android's Medium and Low connection ceilings both map to the module's low tier
                // (the source contract has no Medium).
                "MEDIUM", LOW -> LOW
                else -> HIGH
            }
        }
        val module = manager.modules(indexUrl).getOrElse { return Result.failure(it) }
            .firstOrNull { it.id == reference.moduleId }
            ?: return Result.failure(IllegalStateException("Module ${reference.moduleId} is not in the configured index"))
        return manager.call(
            module,
            indexUrl,
            "getTrackStreamUrl",
            listOf(reference.trackId, quality, contextFor(quality, quality == LOSSLESS)),
        ).mapCatching { raw ->
            val response = json.decodeFromString<DesktopModuleStreamResponse>(raw)
            val url = response.streamUrl?.takeIf { it.isNotBlank() && it.length <= MAX_STREAM_URL_CHARS }
                ?: error("Module ${module.name} returned no stream URL")
            val meta = response.track
            val format = DesktopStreamFormat(
                codec = codecOf(meta?.mimeType, meta?.audioQuality, url),
                kbps = meta?.bitrate ?: bitrateOf(meta?.audioQuality, url),
                sampleRateHz = meta?.sampleRate?.let { if (it < 1000) (it * 1000).toInt() else it.toInt() },
                bitDepth = meta?.bitDepth,
            )
            check(!malformed(url)) { "Module returned an invalid stream URL" }
            DesktopStream(url, format, DesktopModuleHttp.validateStreamHeaders(response.headers), sourceId = config.id)
        }
    }

    fun close() = manager.close()

    fun reload() = manager.close()

    /** Finds a strict same-recording module copy for a YouTube-origin row. */
    suspend fun match(config: DesktopSourceConfig, song: Song): Song? =
        matches(config, song).firstOrNull()

    /** Every copy the configured modules hold of [song], most confident first. */
    suspend fun matches(config: DesktopSourceConfig, song: Song): List<Song> {
        if (!configured(config) || song.title.isBlank() || song.isVideo) return emptyList()
        val indexUrl = config.baseUrl.trim().takeIf(String::isNotBlank) ?: return emptyList()
        val modules = manager.modules(indexUrl).getOrDefault(emptyList())
        for (query in DesktopTrackMatcher.queries(song)) {
            // Preserve the configured index order.
            for (module in modules) {
                val candidates = searchModule(module, config.id, indexUrl, query, 25)
                    .mapNotNull { (it as? SearchResult.Track)?.song }
                DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }?.let { return it }
            }
        }
        return emptyList()
    }

    private suspend fun searchModule(
        module: DesktopSpineModule,
        sourceId: String,
        indexUrl: String,
        query: String,
        limit: Int,
    ): List<SearchResult> = manager.call(
        module,
        indexUrl,
        "searchTracks",
        // Search has no quality preference in Android's module contract; quality is selected only
        // for the stream call.
        listOf(query, limit, contextFor()),
    ).getOrDefault("{\"tracks\":[]}").let { raw ->
        runCatching {
            json.decodeFromString<DesktopModuleSearchResponse>(raw).tracks.map { it.toSearchResult(sourceId, module) }
        }.getOrDefault(emptyList())
    }

    private fun DesktopModuleTrack.toSearchResult(sourceId: String, module: DesktopSpineModule): SearchResult.Track =
        SearchResult.Track(
            Song(
                videoId = TRACK_PREFIX + sourceId + SEPARATOR + module.id + SEPARATOR + id,
                title = title,
                artist = artist.ifBlank { "Unknown Artist" },
                artistId = artistId,
                albumId = albumId,
                albumName = album.ifBlank { null },
                thumbnailUrl = albumCover,
                durationText = duration.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                sourceQuality = qualityTier("$audioQuality $format")
                    ?: availableQualities.maxByOrNull { qualityRank(it) }?.let(::qualityTier),
            ),
        )

    private fun interleave(lists: List<List<SearchResult>>): List<SearchResult> = buildList {
        var rank = 0
        while (lists.any { it.size > rank }) {
            lists.forEach { it.getOrNull(rank)?.let(::add) }
            rank++
        }
    }

    internal fun parseTrack(videoId: String): ModuleTrackRef? {
        if (!videoId.startsWith(TRACK_PREFIX)) return null
        val encoded = videoId.removePrefix(TRACK_PREFIX)
        val first = encoded.indexOf(SEPARATOR)
        if (first < 1) return null
        val firstPart = encoded.substring(0, first)
        val remainder = encoded.substring(first + SEPARATOR.length)
        val second = remainder.indexOf(SEPARATOR)
        return if (second < 1) {
            // Track ids written by the first desktop implementation omitted the source id.
            ModuleTrackRef(null, firstPart, remainder)
        } else {
            ModuleTrackRef(firstPart, remainder.substring(0, second), remainder.substring(second + SEPARATOR.length))
        }
    }

    private fun contextFor(quality: String = "", strict: Boolean = false): DesktopJsJson = DesktopJsJson(buildJsonObject {
        put("settings", buildJsonObject {
            if (quality.isNotBlank()) put("quality", buildJsonObject { put("value", quality) })
            if (quality.isNotBlank()) {
                put("fallbackMode", buildJsonObject { put("value", if (strict) "strict" else "flexible") })
            }
        })
    }.toString())

    internal fun qualityTier(text: String): String? {
        val value = text.uppercase(Locale.ROOT)
        return when {
            "ATMOS" in value || "DOLBY" in value || "EAC3" in value || "E-AC-3" in value -> DOLBY
            "LOSSLESS" in value || "FLAC" in value || "ALAC" in value ||
                "HI-RES" in value || "HIRES" in value || "24-BIT" in value -> LOSSLESS
            Regex("\\b(320|256|192)\\s*KBPS\\b").containsMatchIn(value) || "HIGH" in value -> HIGH
            Regex("\\b(64|96|128)\\s*KBPS\\b").containsMatchIn(value) || "LOW" in value -> LOW
            else -> null
        }
    }

    private fun qualityRank(value: String): Int = when (qualityTier(value)) {
        DOLBY -> 4
        LOSSLESS -> 3
        HIGH -> 2
        LOW -> 1
        else -> 0
    }

    private fun codecOf(mime: String?, quality: String?, url: String): String? =
        mime?.substringAfterLast('/')?.substringBefore(';')?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: if (qualityTier(quality.orEmpty()) == LOSSLESS) "flac"
            else url.substringBefore('?').substringAfterLast('.')
                .lowercase(Locale.ROOT).takeIf { it in AUDIO_EXTENSIONS }

    private fun bitrateOf(quality: String?, url: String): Int? {
        val text = "${quality.orEmpty()} $url"
        return Regex("\\b(8|16|32|48|64|96|128|160|192|256|320|512|768|1411)\\s*kbps\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: when (qualityTier(quality.orEmpty())) { HIGH -> 320; LOW -> 128; else -> null }
    }

    /** Rejects the malformed/error URLs the Android module source rejects. */
    private fun malformed(url: String): Boolean {
        val parsed = runCatching { URI.create(url) }.getOrNull() ?: return true
        if (url.length !in 1..MAX_STREAM_URL_CHARS || !DesktopModuleHttp.isHttpUri(parsed)) return true
        val origin = "${parsed.scheme}://${parsed.rawAuthority}"
        val pathStart = url.indexOf('/', url.indexOf("://") + 3)
        return pathStart >= 0 && url.indexOf(origin, pathStart) >= 0
    }

    private const val MAX_STREAM_URL_CHARS = 4_096
    private val AUDIO_EXTENSIONS = setOf("flac", "alac", "wav", "aiff", "mp3", "aac", "m4a", "webm", "opus")
}
