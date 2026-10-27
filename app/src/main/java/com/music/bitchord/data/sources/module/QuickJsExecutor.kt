package com.music.bitchord.data.sources.module

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.Http
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.quickjs.QuickJsLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Sandboxed QuickJS engine pool for executing module JS.
 *
 * Modules commonly stash session/auth state in top-level variables set up
 * once at load time (e.g. an eager token pre-fetch). The old one-shot
 * executor re-evaluated the whole script on every function call, so that
 * state never survived from `searchTracks()` to `getStreamUrl()` — each
 * ran in its own throwaway VM. Keeping one engine alive per loaded module,
 * reused across calls, is what a normal module host does and what these
 * modules assume. LRU-capped at [MAX_MODULES]; least-recently-used is
 * evicted to make room rather than refusing to load past the cap.
 *
 * ### Why a module gets several engines and not one
 *
 * A QuickJS VM is a single-threaded interpreter and [callExport] drives it
 * across two `evaluate` calls with the answer parked in a global in between.
 * Both of those make one engine strictly one call at a time: two callers
 * sharing it would interleave their `evaluate`s inside the interpreter, and
 * even if they didn't, the second would overwrite `__spine_resolved_json`
 * before the first had read it — so one track's download would quietly receive
 * another track's stream URL. That was survivable while downloads drained one
 * at a time and playback asked for one track at a time; it is not survivable
 * with several downloads in flight.
 *
 * Serialising on a single engine would be correct and would also give all the
 * concurrency back, since a module's own HTTP fetches happen *inside* the VM
 * and hold it for their whole duration — measured at 7.7s for one module on
 * one query. So each module gets a small pool instead: [ENGINES_PER_MODULE]
 * independent VMs, each handed to one caller at a time, growing lazily so a
 * module nobody is hammering still costs exactly one.
 *
 * Ported from Convx's `QuickJsExecutor`, with the pool added.
 */
internal object QuickJsExecutor {

    private const val TAG = "BitChord"
    private const val MAX_CALL_ARGUMENTS = 8
    private const val MAX_CALL_ARGUMENT_CHARS = 64 * 1024
    private const val MAX_MODULE_RESULT_CHARS = 4 * 1024 * 1024
    private const val MAX_FETCH_URL_CHARS = 4_096
    private const val MAX_FETCH_HEADERS_JSON_CHARS = 16 * 1024
    private const val MAX_FETCH_HEADER_COUNT = 40
    private const val MAX_FETCH_HEADER_NAME_CHARS = 128
    private const val MAX_FETCH_HEADER_VALUE_CHARS = 8_192
    private const val MAX_FETCH_REQUEST_BODY_BYTES = 1024 * 1024
    private const val MAX_FETCH_RESPONSE_BODY_BYTES = 4 * 1024 * 1024
    private const val MAX_TIMER_DELAY_MS = 60_000L
    private const val MAX_MODULE_JS_MEMORY_BYTES = 32L * 1024L * 1024L
    private const val MODULE_EVALUATION_TIMEOUT_MS = 30_000L
    private val ALLOWED_EXPORTS = setOf("searchTracks", "getTrackStreamUrl")

    /**
     * How many modules stay resident.
     *
     * Has to cover a whole index, not a working set. A search fans out to
     * *every* module at once, so a cap below the index size means each search
     * evicts engines the same search is still using — and the eviction is
     * invisible to [ModuleManager], which caches the load separately and goes
     * on reporting a hit for an engine that is gone. What that produced was
     * `Module … is not loaded` on the stream call for whichever modules lost
     * the race, one wasted attempt per track, on every track. Four was the
     * ported default and is below the size of a real index.
     */
    private const val MAX_MODULES = 12

    /**
     * How many callers one module can serve at once.
     *
     * Each is a whole interpreter with the module's script evaluated into it,
     * so this is not free — and each costs whatever the module does at load
     * time, which for several of them is an eager token fetch. Three is sized
     * against the download queue's own width: enough that the workers are not
     * queueing behind each other on the one slow module in the index, few
     * enough that a three-module index is nine VMs and not thirty.
     */
    private const val ENGINES_PER_MODULE = 3

    /**
     * One module's interpreters, and the source to make more from.
     *
     * [free] is the ones nobody is inside right now. It is unbounded and only
     * ever holds engines this pool made, so a send to it cannot fail or block;
     * a receive is a caller waiting its turn.
     */
    private class Pool(val jsCode: String, val fetchBase: String) {
        val free = Channel<QuickJs>(Channel.UNLIMITED)
        val lock = Any()
        /** Engines made or being made — the claim that stops two callers both growing the pool. */
        var started = 0
        /** Engines actually made, for closing idle contexts on unload. Guarded by [lock]. */
        val made = mutableListOf<QuickJs>()
        /** Engines currently in callExport. Guarded by [lock]. */
        val inUse = mutableSetOf<QuickJs>()
        /** Retired pools cannot hand out or accept engines. Guarded by [lock]. */
        var retired = false
    }

    /** Whether [moduleId] still has a live pool, i.e. hasn't been evicted. */
    fun isLoaded(moduleId: String): Boolean =
        synchronized(engineLock) { pools.containsKey(moduleId) }

    private val syncHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val engineLock = Any()

    /** LRU map: access-ordered so the oldest-used entry is first. */
    private val pools = LinkedHashMap<String, Pool>(16, 0.75f, true)

    // ── Load ─────────────────────────────────────────────────────────────

    suspend fun loadModule(moduleId: String, jsCode: String, fetchBase: String = ""): Result<Unit> {
        val evicted = synchronized(engineLock) {
            if (pools.containsKey(moduleId)) {
                TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) — ENGINE CACHE HIT")
                return Result.success(Unit)
            }
            buildList<Pool> {
                while (pools.size >= MAX_MODULES) {
                    val lruId = pools.keys.firstOrNull() ?: break
                    TrackLog.d(TAG, "  Evicting LRU module engine: $lruId")
                    pools.remove(lruId)?.let(::add)
                }
            }
        }
        evicted.forEach(::retirePool)

        TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) fetchBase=${redactModuleUrl(fetchBase)} jsCodeLength=${jsCode.length}")
        return withContext(Dispatchers.Default) {
            runCatching { newEngine(jsCode, fetchBase) }
                .onSuccess { qjs ->
                    // The pool starts at one and grows only if two callers ever
                    // want this module at the same moment. A module that is
                    // never contended never pays for a second interpreter.
                    val pool = Pool(jsCode, fetchBase)
                    pool.started = 1
                    pool.made += qjs
                    pool.free.trySend(qjs)
                    synchronized(engineLock) { pools[moduleId] = pool }
                }
                .onFailure { TrackLog.e(TAG, "  ✗ loadModule FAILED for $moduleId (${it::class.simpleName})") }
                .map { }
        }
    }

    /** A fresh interpreter with [jsCode] evaluated into it, or a throw saying why not. */
    private suspend fun newEngine(jsCode: String, fetchBase: String): QuickJs {
        val qjs = QuickJsLifecycle.create(Dispatchers.Default)
        qjs.memoryLimit = MAX_MODULE_JS_MEMORY_BYTES
        qjs.maxStackSize = 512 * 1024L
        qjs.evaluationTimeoutMillis = MODULE_EVALUATION_TIMEOUT_MS
        try {
            bindConsole(qjs)
            bindAsyncFetch(qjs, fetchBase)

            qjs.evaluate<String>(POLYFILLS)
            val cleanCode = preprocessModuleCode(jsCode)

            qjs.evaluate<String>(
                """
                var __spine_iife_error = null;
                var __spine_mod = (function() {
                    try {
                        var module = { exports: {} };
                        var exports = module.exports;
                        var self = {};
                        $cleanCode
                        if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                            return module.exports;
                        }
                        return {};
                    } catch(e) {
                        __spine_iife_error = e && e.message ? e.message : String(e);
                        return {};
                    }
                })();
                'ok'
                """.trimIndent()
            )

            val iifeError = qjs.evaluate<String>("__spine_iife_error || 'none'")
            if (iifeError != "none") throw IllegalStateException("Module init error: $iifeError")

            val keys = qjs.evaluate<String>("Object.keys(__spine_mod).join(', ')")
            TrackLog.d(TAG, "  Module exports: [$keys]")
            return qjs
        } catch (e: Throwable) {
            QuickJsLifecycle.close(qjs)
            throw e
        }
    }

    // ── Pool ─────────────────────────────────────────────────────────────

    /**
     * An interpreter of [moduleId]'s, exclusively, until it is given back.
     *
     * Takes a free one if there is one, makes another if the pool has room, and
     * otherwise waits for whoever is using one to finish. The claim on a new
     * engine is taken *before* it is built and released if building throws, so
     * a module whose script fails to evaluate cannot permanently consume a slot
     * in the pool it never joined.
     */
    private suspend fun acquire(moduleId: String, pool: Pool): QuickJs {
        val available = synchronized(pool.lock) {
            check(!pool.retired) { "Module $moduleId was unloaded" }
            pool.free.tryReceive().getOrNull()?.also { pool.inUse += it }
        }
        if (available != null) return available

        val mine = synchronized(pool.lock) {
            check(!pool.retired) { "Module $moduleId was unloaded" }
            if (pool.started >= ENGINES_PER_MODULE) {
                false
            } else {
                pool.started++
                true
            }
        }
        if (!mine) {
            val waiting = pool.free.receive()
            val accepted = synchronized(pool.lock) {
                if (pool.retired) false else {
                    pool.inUse += waiting
                    true
                }
            }
            check(accepted) { "Module $moduleId was unloaded" }
            return waiting
        }

        return try {
            val fresh = newEngine(pool.jsCode, pool.fetchBase)
            val accepted = synchronized(pool.lock) {
                if (pool.retired) {
                    pool.started--
                    false
                } else {
                    pool.made += fresh
                    pool.inUse += fresh
                    true
                }
            }
            if (!accepted) {
                QuickJsLifecycle.close(fresh)
                error("Module $moduleId was unloaded")
            }
            fresh
        } catch (e: Throwable) {
            synchronized(pool.lock) {
                // If the pool retired after this build slot was reserved, the
                // retirement branch already released it above.
                if (!pool.retired) pool.started--
            }
            throw e
        }
    }

    // ── Call ─────────────────────────────────────────────────────────────

    suspend fun callExport(moduleId: String, functionName: String, args: List<String>): Result<String> {
        if (functionName !in ALLOWED_EXPORTS) {
            return Result.failure(SecurityException("Unsupported module export"))
        }
        val pool = synchronized(engineLock) { pools[moduleId] }
            ?: return Result.failure(IllegalStateException("Module $moduleId is not loaded"))

        TrackLog.d(TAG, "QuickJsExecutor.callExport($moduleId, $functionName) argumentCount=${args.size}")
        return withContext(Dispatchers.Default) {
            val qjs = try {
                acquire(moduleId, pool)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext Result.failure(e)
            }
            try {
                callOn(qjs, moduleId, functionName, args)
            } finally {
                // Back into circulation on every path, including a throw: an
                // engine that is never returned is a slot the pool can never
                // hand out again, and three of those would deadlock the module.
                release(pool, qjs)
            }
        }
    }

    private fun release(pool: Pool, engine: QuickJs) {
        val closeNow = synchronized(pool.lock) {
            pool.inUse.remove(engine)
            if (pool.retired) {
                pool.made.remove(engine)
                true
            } else {
                val returned = pool.free.trySend(engine).isSuccess
                if (!returned) {
                    pool.made.remove(engine)
                    pool.started--
                    true
                } else {
                    false
                }
            }
        }
        if (closeNow) runCatching { QuickJsLifecycle.close(engine) }
    }

    private fun retirePool(pool: Pool) {
        val idleEngines = synchronized(pool.lock) {
            if (pool.retired) return
            pool.retired = true
            pool.free.close()
            val idle = pool.made.filterNot { it in pool.inUse }
            pool.made.removeAll(idle.toSet())
            idle
        }
        idleEngines.forEach { runCatching { QuickJsLifecycle.close(it) } }
    }

    private suspend fun callOn(
        qjs: QuickJs,
        moduleId: String,
        functionName: String,
        args: List<String>,
    ): Result<String> =
        run {
            runCatching {
                require(args.size <= MAX_CALL_ARGUMENTS && args.sumOf(String::length) <= MAX_CALL_ARGUMENT_CHARS) {
                    "Module call arguments exceed the allowed size"
                }
                // Parse and re-serialize as one JSON array so data values cannot
                // escape their argument position when inserted into JavaScript.
                val argsStr = JSONArray(args.joinToString(separator = ",", prefix = "[", postfix = "]"))
                    .toString()
                    .removeSurrounding("[", "]")
                val functionNameJson = JSONObject.quote(functionName)
                val hasFn = qjs.evaluate<String>("typeof __spine_mod[$functionNameJson]")
                if (hasFn != "function") {
                    throw IllegalStateException("$functionName is not a function on module $moduleId")
                }

                // evaluate() converts a Promise via toString() instead of awaiting it.
                // Workaround: store the resolved JSON in a global var inside the async
                // IIFE, then read it in a second evaluate() call.
                qjs.evaluate<String>(
                    """
                    var __spine_resolved_json = undefined;
                    (async function() {
                        var __fn = __spine_mod[$functionNameJson];
                        if (!__fn) {
                            __spine_resolved_json = JSON.stringify({ error: ${JSONObject.quote("$functionName not found")} });
                            return;
                        }
                        try {
                            var r = await __fn($argsStr);
                            __spine_resolved_json = typeof r === 'string' ? r : JSON.stringify(r);
                        } catch(e) {
                            __spine_resolved_json = JSON.stringify({ error: e && e.message ? e.message : String(e) });
                        }
                    })();
                    """.trimIndent()
                )

                val rawResult = qjs.evaluate<String>("__spine_resolved_json")
                require(rawResult.length <= MAX_MODULE_RESULT_CHARS) {
                    "Module result exceeds the allowed size"
                }
                TrackLog.d(TAG, "  callExport result (${rawResult.length} chars)")
                rawResult
            }.onFailure {
                TrackLog.e(TAG, "  ✗ callExport FAILED for $moduleId.$functionName (${it::class.simpleName})")
            }
        }

    // ── Unload ───────────────────────────────────────────────────────────

    fun unload(moduleId: String) {
        val pool = synchronized(engineLock) { pools.remove(moduleId) }
        pool?.let(::retirePool)
        TrackLog.d(TAG, "QuickJsExecutor.unload($moduleId)")
    }

    fun unloadAll() {
        val all = synchronized(engineLock) {
            val values = pools.values.toList()
            pools.clear()
            values
        }
        all.forEach(::retirePool)
        TrackLog.d(TAG, "QuickJsExecutor.unloadAll()")
    }

    // ── Code pre-processing ──────────────────────────────────────────────

    /**
     * Strips ES-module `export` keywords so the code runs inside an IIFE
     * that wraps it in a CommonJS-style `module.exports` object.
     *
     * Also handles the template-literal export format some Spine sources use:
     * `export const x = \`…actual JS…\``
     */
    private fun preprocessModuleCode(jsCode: String): String {
        val code = jsCode.trim()

        val exportPattern = Regex("""^export\s+const\s+\w+\s*=\s*`""")
        val exportMatch = exportPattern.find(code)
        if (exportMatch != null) {
            val contentStart = exportMatch.range.last + 1
            var i = contentStart
            while (i < code.length) {
                if (code[i] == '\\' && i + 1 < code.length) { i += 2; continue }
                if (code[i] == '`') {
                    return code.substring(contentStart, i).trim()
                }
                i++
            }
            TrackLog.w(TAG, "  Template literal not closed, falling through to regex preprocess")
        }

        var result = code
        result = result.replace(Regex("""\bexport\s+default\s+(?=function|class|const|let|var|async)"""), "")
        result = result.replace(Regex("""\bexport\s+(const|let|var|function|class|async)\b"""), "$1")
        result = result.replace(Regex("""\bexport\s*\{[^}]*\}\s*;?"""), "")
        return result
    }

    // ── Console binding ──────────────────────────────────────────────────

    private fun bindConsole(qjs: QuickJs) {
        qjs.define("console") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.d(TAG, "[JS] module log event (${args.size} values)")
                }
            })
            function("error", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.e(TAG, "[JS-ERR] module error event (${args.size} values)")
                }
            })
            function("warn", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.w(TAG, "[JS-WARN] module warning event (${args.size} values)")
                }
            })
            function("info", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.i(TAG, "[JS-INFO] module info event (${args.size} values)")
                }
            })
        }
    }

    // ── Fetch binding ────────────────────────────────────────────────────

    private suspend fun bindAsyncFetch(qjs: QuickJs, fetchBase: String) {
        qjs.define("__spine") {
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val rawUrl = args[0]?.toString()
                        ?: throw IllegalArgumentException("fetch requires a URL")
                    val method = args[1]?.toString() ?: "GET"
                    val headersJson = args[2]?.toString() ?: "{}"
                    val body = args[3]?.toString()
                    require(rawUrl.length <= MAX_FETCH_URL_CHARS) { "fetch URL is too long" }
                    val url = resolveUrl(rawUrl, fetchBase)

                    TrackLog.d(TAG, "  → fetch $method ${redactModuleUrl(url)}")
                    val (statusCode, responseBody) = fetchUrlSync(url, method, headersJson, body)
                    TrackLog.d(TAG, "    HTTP $statusCode (${responseBody.length} bytes)")

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
                    val ms = args.getOrNull(1)?.toString()?.toLongOrNull()?.coerceIn(0L, MAX_TIMER_DELAY_MS) ?: 0L
                    kotlinx.coroutines.delay(ms)
                    return "0"
                }
            })
            asyncFunction("clearTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String = "ok"
            })
        }

        // Expose a web-compatible fetch() surface over the native binding.
        qjs.evaluate<Unit>(
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
                    if (options.signal && options.signal.aborted) {
                        throw new Error('Aborted');
                    }
                }
                var raw = JSON.parse(await __spine.fetch(url, method, headers, body));
                var respBody = raw.body;
                return {
                    ok: raw.ok,
                    status: raw.status,
                    statusText: raw.ok ? 'OK' : 'Error',
                    json: function() { try { return JSON.parse(respBody); } catch(e) { throw new Error('Invalid JSON: ' + respBody.substring(0, 200)); } },
                    text: function() { return respBody; },
                    arrayBuffer: function() { throw new Error('Not implemented'); },
                    clone: function() { return this; },
                    headers: { get: function(k) { return null; } }
                };
            };

            var setTimeout = async function(fn, ms) {
                await __spine.setTimeout(null, ms || 0);
                if (typeof fn === 'function') fn();
                return 0;
            };
            var clearTimeout = function(id) {};
            """.trimIndent()
        )
    }

    // ── URL resolution ───────────────────────────────────────────────────

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (base.isEmpty()) return url
        return if (url.startsWith("/")) {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://").substringBefore("/")
            "$scheme://$host$url"
        } else {
            "$base/$url"
        }
    }

    private fun fetchUrlSync(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
    ): Pair<Int, String> {
        if (url.length > MAX_FETCH_URL_CHARS) throw IOException("Module fetch URL is too long")
        val parsedUrl = url.toHttpUrlOrNull() ?: throw IOException("Module fetch URL is invalid")
        if (parsedUrl.scheme !in setOf("https", "http")) throw IOException("Unsupported module fetch scheme")
        if (headersJson.length > MAX_FETCH_HEADERS_JSON_CHARS) throw IOException("Module fetch headers are too large")
        if ((body?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) > MAX_FETCH_REQUEST_BODY_BYTES) {
            throw IOException("Module fetch request body is too large")
        }
        val builder = Request.Builder().url(parsedUrl)

        var hasUserAgent = false
        val headersObj = try {
            JSONObject(headersJson)
        } catch (error: Exception) {
            throw IOException("Module fetch headers are invalid", error)
        }
        if (headersObj.length() > MAX_FETCH_HEADER_COUNT) throw IOException("Too many module fetch headers")
        for (key in headersObj.keys()) {
            val rawValue = headersObj.get(key)
            if (rawValue !is String || key.length > MAX_FETCH_HEADER_NAME_CHARS || rawValue.length > MAX_FETCH_HEADER_VALUE_CHARS) {
                throw IOException("Module fetch header is invalid")
            }
            builder.header(key, rawValue)
            if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
        }

        if (!hasUserAgent) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            )
        }

        when (method.uppercase(Locale.ROOT)) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> throw IOException("Unsupported module fetch method")
        }

        syncHttpClient.newCall(builder.build()).execute().use { response ->
            val responseBody = readBoundedResponse(response.body)
            return response.code to responseBody
        }
    }

    private fun readBoundedResponse(body: okhttp3.ResponseBody?): String {
        body ?: return ""
        if (body.contentLength() > MAX_FETCH_RESPONSE_BODY_BYTES) {
            throw IOException("Module response body is too large")
        }
        val output = ByteArrayOutputStream(minOf(MAX_FETCH_RESPONSE_BODY_BYTES, 8_192))
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FETCH_RESPONSE_BODY_BYTES) throw IOException("Module response body is too large")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    // ── Polyfills ────────────────────────────────────────────────────────

    private const val POLYFILLS = """
        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = { aborted: false }; };
            AbortController.prototype.abort = function() { this.signal.aborted = true; };
        }

        if (typeof Object.assign !== 'function') {
            Object.assign = function(target) {
                if (target == null) throw new TypeError('Cannot convert undefined or null to object');
                var to = Object(target);
                for (var i = 1; i < arguments.length; i++) {
                    var source = arguments[i];
                    if (source != null) {
                        for (var key in source) {
                            if (Object.prototype.hasOwnProperty.call(source, key)) {
                                to[key] = source[key];
                            }
                        }
                    }
                }
                return to;
            };
        }

        if (typeof Promise.any !== 'function') {
            Promise.any = function(promises) {
                return new Promise(function(resolve, reject) {
                    var errors = [];
                    var remaining = promises.length;
                    if (remaining === 0) { reject(new AggregateError([], 'All promises were rejected')); return; }
                    promises.forEach(function(p, i) {
                        Promise.resolve(p).then(resolve, function(e) {
                            errors[i] = e;
                            remaining--;
                            if (remaining === 0) reject(new AggregateError(errors, 'All promises were rejected'));
                        });
                    });
                });
            };
        }

        if (typeof Promise.allSettled !== 'function') {
            Promise.allSettled = function(promises) {
                return Promise.all(promises.map(function(p) {
                    return Promise.resolve(p).then(
                        function(value) { return { status: 'fulfilled', value: value }; },
                        function(reason) { return { status: 'rejected', reason: reason }; }
                    );
                }));
            };
        }

        if (typeof AggregateError === 'undefined') {
            var AggregateError = function(errors, message) {
                this.errors = errors;
                this.message = message || '';
                this.name = 'AggregateError';
            };
            AggregateError.prototype = Object.create(Error.prototype);
        }

        if (typeof atob === 'undefined') {
            var atob = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input).replace(/=+${'$'}/, '');
                var output = '';
                for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = chars.indexOf(buffer);
                }
                return output;
            };
        }

        if (typeof setTimeout === 'undefined') {
            var __spine_timers = {};
            var __spine_timer_id = 0;
            var setTimeout = function(fn, ms) {
                var id = ++__spine_timer_id;
                __spine_timers[id] = { fn: fn, ms: ms || 0 };
                return id;
            };
            var clearTimeout = function(id) {
                if (__spine_timers[id]) delete __spine_timers[id];
            };
        }
        if (typeof clearTimeout === 'undefined') {
            var clearTimeout = function(id) {
                if (typeof __spine_timers !== 'undefined' && __spine_timers[id]) delete __spine_timers[id];
            };
        }

        if (typeof URL === 'undefined') {
            var URL = function(url, base) {
                this.href = url;
                try {
                    var a = url.replace(/^[^:]+:/, 'http:');
                    var match = a.match(/^\/\/([^/]+)(\/.*)?$/);
                    if (match) {
                        this.hostname = match[1];
                        this.pathname = match[2] || '/';
                    } else {
                        var m2 = a.match(/^https?:\/\/([^/]+)(\/.*)?$/);
                        if (m2) { this.hostname = m2[1]; this.pathname = m2[2] || '/'; }
                    }
                } catch(e) {}
            };
        }
    """
}
