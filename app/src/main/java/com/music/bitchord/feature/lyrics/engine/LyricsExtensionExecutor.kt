package com.music.bitchord.feature.lyrics.engine

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.Http
import com.music.bitchord.data.quickjs.QuickJsLifecycle
import com.music.bitchord.data.lyrics.LyricsLog
import com.music.bitchord.feature.lyrics.domain.LyricsExtensionSecurity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sandboxed QuickJS VM executor for dynamic lyrics extensions.
 */
object LyricsExtensionExecutor {

    private const val TAG = "LyricsExtension"
    private const val EXECUTION_TIMEOUT_MS = 15_000L
    private const val SEARCH_TIMEOUT_MS = 30_000L
    private const val MAX_EXTENSION_REQUEST_BODY_CHARS = 1_048_576
    private const val MAX_EXTENSION_RESPONSE_BYTES = 2 * 1_048_576

    // QuickJS' Android JNI layer keeps process-global references. Creating or
    // disposing contexts concurrently (or from different Default dispatcher
    // workers) can leave one context with a stale JNI global and abort ART.
    // Keep one VM on one thread and serialize its complete request lifecycle.
    private val quickJsDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LyricsQuickJs").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val engineLock = Mutex()
    private var engine: QuickJs? = null
    private var engineSetupFailed = false
    private var loadedExtensionId: String? = null
    private val invalidatedExtensions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activeNetworkCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var invalidateLoadedExtension = false
    @Volatile private var extensionsEnabled = false
    @Volatile private var activeExtensionId: String? = null
    @Volatile private var activeRequest: ActiveRequest? = null
    private val requestIds = AtomicLong()

    /** Stops extension execution and any network requests as soon as the user opts out. */
    fun setExtensionsEnabled(enabled: Boolean) {
        extensionsEnabled = enabled
        if (!enabled) {
            activeRequest?.result?.complete(null)
            activeNetworkCalls.forEach { call -> call.cancel() }
            unloadAll()
        }
    }

    private data class ActiveRequest(
        val id: Long,
        val extensionId: String,
        val result: CompletableDeferred<String?>,
    )

    private val httpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Executes the `getLyrics` export of the specified extension.
     *
     * @param extensionId Identifier of the extension
     * @param scriptFile Local JavaScript file of the extension
     * @param title Song title
     * @param artist Song artist
     * @param durationMs Song duration in ms
     * @param album Album name (optional)
     * @param videoId Video ID (optional)
     * @return Raw lyrics string (LRC, TTML, plain text) or null
     */
    suspend fun getLyrics(
        extensionId: String,
        scriptFile: File,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        videoId: String? = null,
    ): String? = withContext(quickJsDispatcher) {
        if (!extensionsEnabled) return@withContext null
        engineLock.withLock {
            withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
                try {
                    if (!extensionsEnabled) return@withTimeoutOrNull null
                    val qjs = getOrInitEngine(extensionId, scriptFile) ?: return@withTimeoutOrNull null
                    val trackJson = JSONObject().apply {
                        put("title", title)
                        put("artist", artist)
                        put("durationMs", durationMs)
                        put("album", album ?: "")
                        put("videoId", videoId ?: "")
                    }.toString()

                    LyricsLog.i(extensionId, "Running lyrics lookup")

                    val request = ActiveRequest(requestIds.incrementAndGet(), extensionId, CompletableDeferred())
                    activeExtensionId = extensionId
                    activeRequest = request

                    try {
                        qjs.evaluate<String>(
                            """
                            (async function() {
                                try {
                                    if (__lyricsExtensionModule && typeof __lyricsExtensionModule.getLyrics === 'function') {
                                        var r = await __lyricsExtensionModule.getLyrics($trackJson);
                                        __native_bridge.sendResult((r !== undefined && r !== null) ? String(r) : 'null', ${request.id});
                                    } else {
                                        console.warn('[LyricsExt] getLyrics not found on module.exports');
                                        __native_bridge.sendResult('null', ${request.id});
                                    }
                                } catch(e) {
                                    console.error('[LyricsExt Error] ' + (e && e.message ? e.message : String(e)));
                                    __native_bridge.sendResult('null', ${request.id});
                                }
                            })();
                            'ok';
                            """.trimIndent()
                        )

                        val result = request.result.await()
                        LyricsLog.i(extensionId, "getLyrics result: ${result?.take(100)}")
                        if (result == "null" || result.isNullOrBlank()) null else result
                    } finally {
                        if (activeRequest?.id == request.id) activeRequest = null
                        if (activeExtensionId == extensionId) activeExtensionId = null
                    }
                } catch (e: Throwable) {
                    LyricsLog.w(extensionId, "Execution failed: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Executes the `searchLyrics` export of the specified extension (for lyrics editor dialog).
     */
    suspend fun searchLyrics(
        extensionId: String,
        scriptFile: File,
        title: String,
        artist: String,
        album: String? = null,
    ): String? = withContext(quickJsDispatcher) {
        if (!extensionsEnabled) return@withContext null
        engineLock.withLock {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                try {
                    if (!extensionsEnabled) return@withTimeoutOrNull null
                    val qjs = getOrInitEngine(extensionId, scriptFile) ?: return@withTimeoutOrNull null
                    val queryJson = JSONObject().apply {
                        put("title", title)
                        put("artist", artist)
                        put("album", album ?: "")
                    }.toString()

                    LyricsLog.i(extensionId, "Running lyrics search")

                    val request = ActiveRequest(requestIds.incrementAndGet(), extensionId, CompletableDeferred())
                    activeExtensionId = extensionId
                    activeRequest = request

                    try {
                        qjs.evaluate<String>(
                            """
                            (async function() {
                                try {
                                    if (__lyricsExtensionModule && typeof __lyricsExtensionModule.searchLyrics === 'function') {
                                        var r = await __lyricsExtensionModule.searchLyrics($queryJson);
                                        __native_bridge.sendResult((r !== undefined && r !== null) ? JSON.stringify(r) : '[]', ${request.id});
                                    } else {
                                        console.warn('[LyricsExt] searchLyrics not found on module.exports');
                                        __native_bridge.sendResult('[]', ${request.id});
                                    }
                                } catch(e) {
                                    console.error('[LyricsExt search error] ' + (e && e.message ? e.message : String(e)));
                                    __native_bridge.sendResult('[]', ${request.id});
                                }
                            })();
                            'ok';
                            """.trimIndent()
                        )

                        val result = request.result.await()
                        LyricsLog.i(extensionId, "searchLyrics result: ${result?.take(200)}")
                        if (result == "[]" || result.isNullOrBlank()) null else result
                    } finally {
                        if (activeRequest?.id == request.id) activeRequest = null
                        if (activeExtensionId == extensionId) activeExtensionId = null
                    }
                } catch (e: Throwable) {
                    LyricsLog.e(extensionId, "searchLyrics exception: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Unloads and closes an engine when an extension is updated or disabled.
     */
    fun unload(extensionId: String) {
        // Invalidate synchronously so a request that starts immediately after
        // an extension update cannot reuse its old exports. Never close a JNI
        // VM from this caller thread; the single VM is reused until process exit.
        invalidatedExtensions += extensionId
    }

    /**
     * Unloads and closes all engines.
     */
    fun unloadAll() {
        invalidateLoadedExtension = true
    }

    private suspend fun getOrInitEngine(extensionId: String, scriptFile: File): QuickJs? {
        if (!extensionsEnabled) return null
        if (!scriptFile.exists() || !scriptFile.canRead()) {
            LyricsLog.w(extensionId, "Script file not found: ${scriptFile.absolutePath}")
            return null
        }

        var qjs = engine
        if (qjs == null) {
            if (engineSetupFailed) return null
            qjs = try {
                QuickJsLifecycle.create(quickJsDispatcher).also { created ->
                    engine = created
                    created.maxStackSize = 512 * 1024L
                    bindConsole(created)
                    bindHttp(created)
                    bindBase64(created)
                    bindBridge(created)
                    created.evaluate<String>("'ok';")
                }
            } catch (e: Throwable) {
                engineSetupFailed = true
                LyricsLog.e(extensionId, "Failed to initialize QuickJS: ${e.message}")
                return null
            }
        }

        if (loadedExtensionId == extensionId && extensionId !in invalidatedExtensions && !invalidateLoadedExtension) {
            return qjs
        }

        val code = runCatching { scriptFile.readText() }.getOrNull() ?: return null
        activeExtensionId = extensionId
        return try {
            qjs.evaluate<String>(
                """
                globalThis.__lyricsExtensionModule = (function() {
                    var module = { exports: {} };
                    var exports = module.exports;
                    $code
                    return module.exports;
                })();
                'ok';
                """.trimIndent(),
            )
            loadedExtensionId = extensionId
            invalidatedExtensions.remove(extensionId)
            invalidateLoadedExtension = false
            qjs
        } catch (e: Throwable) {
            loadedExtensionId = null
            LyricsLog.e(extensionId, "Failed to initialize extension JS: ${e.message}")
            null
        }
    }

    private fun bindConsole(qjs: QuickJs) {
        qjs.define("console") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    LyricsLog.i(activeExtensionId ?: TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                }
            })
            function("error", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    LyricsLog.e(activeExtensionId ?: TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                }
            })
            function("warn", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    LyricsLog.w(activeExtensionId ?: TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                }
            })
            function("info", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    LyricsLog.i(activeExtensionId ?: TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                }
            })
        }
    }

    private fun bindBridge(qjs: QuickJs) {
        qjs.define("__native_bridge") {
            function("sendResult", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val result = args.firstOrNull()?.toString()
                    val requestId = args.getOrNull(1)?.toString()?.toLongOrNull() ?: return
                    activeRequest?.takeIf { it.id == requestId }?.result?.complete(result)
                }
            })
        }
    }

    private suspend fun bindBase64(qjs: QuickJs) {
        qjs.define("__base64") {
            function("atob", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val str = args.firstOrNull()?.toString() ?: return ""
                    return runCatching {
                        String(Base64.decode(str, Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrDefault("")
                }
            })
            function("btoa", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val str = args.firstOrNull()?.toString() ?: return ""
                    return runCatching {
                        Base64.encodeToString(str.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    }.getOrDefault("")
                }
            })
        }

        qjs.evaluate<String>(
            """
            var atob = function(str) { return __base64.atob(str); };
            var btoa = function(str) { return __base64.btoa(str); };
            'ok';
            """.trimIndent()
        )
    }

    private suspend fun bindHttp(qjs: QuickJs) {
        qjs.define("__native_lyrics") {
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    if (!extensionsEnabled) throw IllegalStateException("Lyrics extensions are disabled")
                    val rawUrl = args.getOrNull(0)?.toString() ?: throw IllegalArgumentException("fetch requires URL")
                    val method = args.getOrNull(1)?.toString() ?: "GET"
                    val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                    val body = args.getOrNull(3)?.toString()

                    val (statusCode, responseBody) = executeHttp(rawUrl, method, headersJson, body)
                    val respObj = JSONObject().apply {
                        put("status", statusCode)
                        put("ok", statusCode in 200..299)
                        put("body", responseBody)
                    }
                    return respObj.toString()
                }
            })
            asyncFunction("setTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val ms = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
                    delay(ms)
                    return "0"
                }
            })
        }

        // Polyfill standard fetch() over __native_lyrics.fetch
        qjs.evaluate<String>(
            """
            var fetch = async function(url, options) {
                var method = 'GET';
                var headers = '{}';
                var body = null;
                if (options) {
                    method = options.method || 'GET';
                    if (options.headers) {
                        if (typeof options.headers === 'string') {
                            headers = options.headers;
                        } else {
                            try { headers = JSON.stringify(options.headers); } catch(e) { headers = '{}'; }
                        }
                    }
                    if (options.body !== undefined && options.body !== null) {
                        body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                    }
                }
                var raw = JSON.parse(await __native_lyrics.fetch(url, method, headers, body));
                var respBody = raw.body;
                return {
                    ok: raw.ok,
                    status: raw.status,
                    statusText: raw.ok ? 'OK' : 'Error',
                    json: function() {
                        try { return JSON.parse(respBody); } catch(e) { throw new Error('Invalid JSON'); }
                    },
                    text: function() { return respBody; }
                };
            };

            var setTimeout = async function(fn, ms) {
                await __native_lyrics.setTimeout(null, ms || 0);
                if (typeof fn === 'function') fn();
                return 0;
            };
            var clearTimeout = function(id) {};
            'ok';
            """.trimIndent()
        )
    }

    private fun executeHttp(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
    ): Pair<Int, String> = runCatching {
        if (!extensionsEnabled) return@runCatching Pair(0, "")
        if (!LyricsExtensionSecurity.isHttpsUrl(url) || headersJson.length > 16_384 ||
            (body?.length ?: 0) > MAX_EXTENSION_REQUEST_BODY_CHARS
        ) return@runCatching Pair(0, "")
        val builder = Request.Builder().url(url)
        var hasUserAgent = false

        val headersObj = runCatching { JSONObject(headersJson) }.getOrNull()
        if (headersObj != null && headersObj.length() <= 40) {
            for (key in headersObj.keys()) {
                val value = headersObj.optString(key, "")
                if (key.length > 128 || value.length > 8192) continue
                builder.header(key, value)
                if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
            }
        }

        if (!hasUserAgent) {
            builder.header("User-Agent", "BitChord (https://github.com/bitchord)")
        }

        if (method.equals("POST", ignoreCase = true)) {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            builder.post((body ?: "").toRequestBody(mediaType))
        } else {
            builder.get()
        }

        val call = httpClient.newCall(builder.build())
        activeNetworkCalls.add(call)
        try {
            if (!extensionsEnabled) call.cancel()
            call.execute().use { response ->
                val code = response.code
                val responseBody = if (response.request.url.isHttps) {
                    readBoundedBody(response.body, MAX_EXTENSION_RESPONSE_BYTES)
                } else null
                Pair(code, responseBody.orEmpty())
            }
        } finally {
            activeNetworkCalls.remove(call)
        }
    }.getOrElse {
        LyricsLog.w(TAG, "Lyrics extension network request failed")
        Pair(0, "")
    }

    private fun readBoundedBody(body: okhttp3.ResponseBody?, maxBytes: Int): String? {
        body ?: return null
        if (body.contentLength() > maxBytes) return null
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }
}
