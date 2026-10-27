package com.music.bitchord.feature.lyrics.manager

import android.content.Context
import android.util.AtomicFile
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.LyricsLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.lyrics.domain.LyricsExtension
import com.music.bitchord.feature.lyrics.domain.LyricsExtensionManifest
import com.music.bitchord.feature.lyrics.domain.LyricsExtensionSecurity
import com.music.bitchord.feature.lyrics.domain.RemoteLyricsRegistry
import com.music.bitchord.feature.lyrics.engine.LyricsExtensionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages installed lyrics extensions, bundled assets, and remote GitHub synchronization.
 */
object LyricsExtensionManager {

    private const val TAG = "LyricsExtensionManager"
    private const val EXTENSIONS_DIR = "lyrics_extensions"
    private const val ASSETS_DIR = "lyrics_extensions"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationLock = Mutex()
    private val syncRunning = AtomicBoolean(false)
    private val activeSyncCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    @Volatile private var extensionsEnabled = false

    private val _installedExtensions = MutableStateFlow<List<LyricsExtension>>(emptyList())
    val installedExtensions: StateFlow<List<LyricsExtension>> = _installedExtensions.asStateFlow()

    private val _dynamicSources = MutableStateFlow<List<com.music.bitchord.data.lyrics.LyricsSource>>(emptyList())
    val dynamicSources: StateFlow<List<com.music.bitchord.data.lyrics.LyricsSource>> = _dynamicSources.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private var appContext: Context? = null

    /**
     * Initializes the manager with the Application Context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        setEnabled(AppSettings.lyricsExtensionsEnabled.value)
    }

    /** Enables bundled/installed scripts only after explicit user consent. */
    fun setEnabled(enabled: Boolean) {
        extensionsEnabled = enabled
        LyricsExtensionExecutor.setExtensionsEnabled(enabled)
        if (!enabled) {
            activeSyncCalls.forEach { call -> call.cancel() }
            _installedExtensions.value = emptyList()
            publishSources(emptyList())
            // Keep the user's provider choices. The IDs can be resolved again
            // when extensions are re-enabled; disabling execution must not
            // silently erase their selection or ordering.
            _syncMessage.value = null
            return
        }

        val context = appContext ?: return
        scope.launch {
            initializationLock.withLock {
                if (!extensionsEnabled) return@withLock
                bootstrapBundledExtensions(context)
                if (!extensionsEnabled) return@withLock
                loadInstalledExtensions(context)
                if (extensionsEnabled && AppSettings.lyricsAutoUpdate.value) {
                    syncFromRepository(context, force = false)
                }
            }
        }
    }

    /**
     * Ensures all bundled extensions from assets exist in the internal storage
     * directory, and overwrites them when the bundled version is newer than
     * what's currently installed (e.g. after an app update).
     */
    private suspend fun bootstrapBundledExtensions(context: Context) = withContext(Dispatchers.IO) {
        if (!extensionsEnabled) return@withContext
        val rootDir = File(context.filesDir, EXTENSIONS_DIR)
        val extDir = File(rootDir, "extensions")
        if (!extDir.exists()) extDir.mkdirs()

        runCatching {
            val assetManager = context.assets
            val (assetRoot, bundledDirsList) = bundledExtensionAssets(context)
            val bundledDirs = bundledDirsList.toTypedArray()

            for (extName in bundledDirs) {
                if (!extensionsEnabled) return@withContext
                if (!LyricsExtensionSecurity.isSafeExtensionId(extName)) continue
                val targetDir = File(extDir, extName)
                if (!targetDir.exists()) targetDir.mkdirs()

                val manifestFile = File(targetDir, "manifest.json")
                val scriptFile = File(targetDir, "index.js")
                val checksumFile = File(targetDir, SCRIPT_CHECKSUM_FILE)
                val assetPrefix = "$assetRoot/$extName"

                // Read bundled manifest version to decide whether to overwrite
                val bundledVersion = runCatching {
                    assetManager.open("$assetPrefix/manifest.json").bufferedReader().use { reader ->
                        val manifest = json.decodeFromString<LyricsExtensionManifest>(reader.readText())
                        require(manifest.id == extName)
                        manifest.version
                    }
                }.getOrNull()

                val installedVersion = if (manifestFile.exists()) {
                    runCatching {
                        json.decodeFromString<LyricsExtensionManifest>(manifestFile.readText()).version
                    }.getOrNull()
                } else null

                val shouldOverwrite = !manifestFile.exists()
                    || !scriptFile.isFile
                    || scriptFile.length() !in 1..MAX_SCRIPT_BYTES.toLong()
                    || (bundledVersion != null && (installedVersion == null || isNewerVersion(bundledVersion, installedVersion) || bundledVersion != installedVersion))

                if (shouldOverwrite) {
                    val manifestText = assetManager.open("$assetPrefix/manifest.json").bufferedReader().use { it.readText() }
                    val scriptText = assetManager.open("$assetPrefix/index.js").bufferedReader().use { it.readText() }
                    require(manifestText.toByteArray(StandardCharsets.UTF_8).size <= MAX_MANIFEST_BYTES)
                    require(scriptText.isNotBlank() && scriptText.toByteArray(StandardCharsets.UTF_8).size <= MAX_SCRIPT_BYTES)
                    writeAtomically(manifestFile, manifestText)
                    writeAtomically(scriptFile, scriptText)
                    writeAtomically(checksumFile, sha256(scriptText))
                    // Clear cached engine so the new script is loaded fresh
                    LyricsExtensionExecutor.unload(extName)
                    LyricsLog.i(TAG, "Bootstrapped $extName: ${installedVersion ?: "new"} -> ${bundledVersion ?: "?"}")
                }
            }
        }.onFailure {
            LyricsLog.w(TAG, "Failed bootstrapping bundled extensions: ${it.message}")
        }
    }

    /**
     * Loads installed extensions from disk into memory.
     */
    suspend fun loadInstalledExtensions(context: Context) = withContext(Dispatchers.IO) {
        if (!extensionsEnabled) {
            _installedExtensions.value = emptyList()
            publishSources(emptyList())
            return@withContext
        }
        val extDir = File(context.filesDir, "$EXTENSIONS_DIR/extensions")
        if (!extDir.exists()) {
            _installedExtensions.value = emptyList()
            publishSources(emptyList())
            return@withContext
        }

        val bundledIds = bundledExtensionAssets(context).second.toSet()
        val list = mutableListOf<LyricsExtension>()
        extDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (!LyricsExtensionSecurity.isSafeExtensionId(dir.name)) return@forEach
            val manifestFile = File(dir, "manifest.json")
            val scriptFile = File(dir, "index.js")
            val checksumFile = File(dir, SCRIPT_CHECKSUM_FILE)
            if (manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES.toLong() &&
                scriptFile.isFile && scriptFile.length() in 1..MAX_SCRIPT_BYTES.toLong()
            ) {
                runCatching {
                    val content = manifestFile.readText()
                    val manifest = json.decodeFromString<LyricsExtensionManifest>(content)
                    validateManifest(manifest, dir.name)
                    val actualChecksum = sha256(scriptFile.readBytes())
                    if (checksumFile.exists()) {
                        val expectedChecksum = checksumFile.readText().trim().lowercase(Locale.ROOT)
                        require(LyricsExtensionSecurity.isSha256(expectedChecksum) && expectedChecksum == actualChecksum) {
                            "Extension script integrity check failed"
                        }
                    } else {
                        // One-time migration for scripts installed before checksums
                        // were recorded. The app-private files directory prevents
                        // other apps from replacing this pin.
                        writeAtomically(checksumFile, actualChecksum)
                    }
                    list += LyricsExtension(
                        id = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        description = manifest.description,
                        author = manifest.author,
                        wordSynced = manifest.wordSynced,
                        defaultPriority = manifest.defaultPriority,
                        scriptFile = scriptFile,
                        isBundled = dir.name in bundledIds,
                    )
                }.onFailure {
                    LyricsLog.w(TAG, "Failed to load extension in ${dir.name}: ${it.message}")
                }
            }
        }

        list.sortBy { it.defaultPriority }
        if (!extensionsEnabled) return@withContext
        _installedExtensions.value = list
        publishSources(list)
        LyricsLog.i(TAG, "Loaded ${list.size} lyrics extensions: ${list.joinToString { it.name }}")
    }

    /** Native providers are always available; external scripts remain separately gated. */
    private fun publishSources(extensions: List<LyricsExtension>) {
        val scriptSources = extensions
            .asSequence()
            .map { com.music.bitchord.data.lyrics.LyricsSource.fromExtension(it) }
            .filterNot { it.id in com.music.bitchord.data.lyrics.BuiltinLyricsSources.nativeIds }
            .toList()
        val sources = com.music.bitchord.data.lyrics.BuiltinLyricsSources.sources + scriptSources
        _dynamicSources.value = sources
        AppSettings.refreshLyricsSources(sources)
    }

    /**
     * Syncs extensions from the remote GitHub registry.
     */
    suspend fun syncFromRepository(
        context: Context? = null,
        force: Boolean = true,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val ctx = context ?: appContext
            ?: return@withContext Result.failure(IllegalStateException("No context available"))
        if (!extensionsEnabled) {
            return@withContext Result.failure(IllegalStateException("Lyrics extensions are disabled"))
        }
        if (!syncRunning.compareAndSet(false, true)) return@withContext Result.success(0)
        _isSyncing.value = true
        _syncMessage.value = "Checking for extension updates..."

        try {
            val repoUrl = AppSettings.lyricsExtensionRepoUrl.value.trim()
            if (!LyricsExtensionSecurity.isHttpsUrl(repoUrl)) {
                _syncMessage.value = "Use a valid HTTPS repository URL"
                return@withContext Result.failure(IllegalArgumentException("Invalid lyrics registry URL"))
            }

            LyricsLog.i(TAG, "Fetching the configured lyrics registry")
            val registryJson = requestText(repoUrl, MAX_REGISTRY_BYTES)
                ?: throw IllegalStateException("Could not read a valid lyrics registry")

            val remoteRegistry = json.decodeFromString<RemoteLyricsRegistry>(registryJson)
            require(remoteRegistry.extensions.size <= MAX_EXTENSION_COUNT) { "Registry contains too many extensions" }
            require(remoteRegistry.extensions.map { it.id }.toSet().size == remoteRegistry.extensions.size) {
                "Registry contains duplicate extension IDs"
            }
            require(remoteRegistry.extensions.all { LyricsExtensionSecurity.isSafeExtensionId(it.id) }) {
                "Registry contains an invalid extension ID"
            }
            require(remoteRegistry.extensions.all { item ->
                item.name.length in 1..160 && item.version.length in 1..80 &&
                    item.description.length <= 2_048 && item.author.length <= 160 &&
                    (item.scriptSha256 == null || LyricsExtensionSecurity.isSha256(item.scriptSha256))
            }) { "Registry contains invalid extension metadata" }
            if (!extensionsEnabled) return@withContext Result.failure(IllegalStateException("Lyrics extensions are disabled"))
            val currentMap = _installedExtensions.value.associateBy { it.id }
            val extDir = File(ctx.filesDir, "$EXTENSIONS_DIR/extensions")
            if (!extDir.exists()) extDir.mkdirs()

            // 1. Prune deleted extensions (folders on disk whose ID is no longer in the remote registry)
            val remoteIds = remoteRegistry.extensions.map { it.id }.toSet()
            val bundledIds = bundledExtensionAssets(ctx).second.toSet()
            extDir.listFiles()?.filter { it.isDirectory && it.name !in remoteIds && it.name !in bundledIds }?.forEach { staleDir ->
                if (!extensionsEnabled) return@withContext Result.failure(IllegalStateException("Lyrics extensions are disabled"))
                LyricsLog.w(TAG, "Pruning deleted extension: ${staleDir.name}")
                LyricsExtensionExecutor.unload(staleDir.name)
                staleDir.deleteRecursively()
            }

            var updatedCount = 0

            for (remoteItem in remoteRegistry.extensions) {
                if (!extensionsEnabled) return@withContext Result.failure(IllegalStateException("Lyrics extensions are disabled"))
                val current = currentMap[remoteItem.id]
                val isRemoteOlder = current != null && isNewerVersion(current.version, remoteItem.version)
                val needsUpdate = !isRemoteOlder && (force || current == null || isNewerVersion(remoteItem.version, current.version))

                if (needsUpdate) {
                    _syncMessage.value = "Updating ${remoteItem.name} (${remoteItem.version})..."
                    LyricsLog.i(TAG, "Downloading update for ${remoteItem.id}: ${current?.version} -> ${remoteItem.version}")

                    val scriptUrl = remoteItem.script_url
                        ?: repoUrl.substringBeforeLast("/") + "/extensions/${remoteItem.id}/index.js"
                    val manifestUrl = remoteItem.manifest_url
                        ?: repoUrl.substringBeforeLast("/") + "/extensions/${remoteItem.id}/manifest.json"

                    if (!LyricsExtensionSecurity.isHttpsUrl(scriptUrl) || !LyricsExtensionSecurity.isHttpsUrl(manifestUrl)) {
                        LyricsLog.w(TAG, "Skipping ${remoteItem.id}: extension URLs must use HTTPS")
                        continue
                    }

                    val itemDir = File(extDir, remoteItem.id)
                    if (!itemDir.exists()) itemDir.mkdirs()

                    val downloadedScript = downloadString(scriptUrl, MAX_SCRIPT_BYTES)
                    val downloadedManifest = downloadString(manifestUrl, MAX_MANIFEST_BYTES)

                    if (!extensionsEnabled) return@withContext Result.failure(IllegalStateException("Lyrics extensions are disabled"))
                    val manifest = downloadedManifest?.let {
                        runCatching { json.decodeFromString<LyricsExtensionManifest>(it) }.getOrNull()
                    }
                    val actualChecksum = downloadedScript?.let(::sha256)
                    val checksumMatches = remoteItem.scriptSha256
                        ?.lowercase(Locale.ROOT)
                        ?.let { expected -> expected == actualChecksum }
                        ?: true
                    if (downloadedScript != null && downloadedScript.isNotBlank() &&
                        downloadedManifest != null && manifest != null &&
                        runCatching { validateManifest(manifest, remoteItem.id) }.isSuccess &&
                        checksumMatches && actualChecksum != null
                    ) {
                        val scriptFile = File(itemDir, "index.js")
                        val manifestFile = File(itemDir, "manifest.json")
                        val checksumFile = File(itemDir, SCRIPT_CHECKSUM_FILE)
                        writeAtomically(scriptFile, downloadedScript)
                        writeAtomically(manifestFile, downloadedManifest)
                        writeAtomically(checksumFile, actualChecksum)
                        LyricsExtensionExecutor.unload(remoteItem.id)
                        updatedCount++
                    } else {
                        LyricsLog.w(TAG, "Skipping ${remoteItem.id}: downloaded extension data was invalid")
                    }
                }
            }

            AppSettings.setLastLyricsExtensionSync(System.currentTimeMillis())
            loadInstalledExtensions(ctx)
            _syncMessage.value = if (updatedCount > 0) "$updatedCount extension(s) updated" else "All extensions up to date"
            Result.success(updatedCount)
        } catch (e: Throwable) {
            if (extensionsEnabled) {
                LyricsLog.e(TAG, "Sync failed: ${e.javaClass.simpleName}")
                _syncMessage.value = "Sync failed. Check the registry URL and connection."
            } else {
                _syncMessage.value = "Lyrics extensions are disabled"
            }
            Result.failure(e)
        } finally {
            _isSyncing.value = false
            syncRunning.set(false)
        }
    }

    private fun downloadString(url: String, maxBytes: Int): String? = runCatching {
        requestText(url, maxBytes)
    }.getOrNull()

    private fun bundledExtensionAssets(context: Context): Pair<String, List<String>> {
        val nestedRoot = "$ASSETS_DIR/extensions"
        val nested = runCatching { context.assets.list(nestedRoot)?.toList().orEmpty() }.getOrDefault(emptyList())
        if (nested.isNotEmpty()) return nestedRoot to nested
        val flat = runCatching { context.assets.list(ASSETS_DIR)?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it != "registry.json" && it != "README.md" && !it.contains(".") }
        return ASSETS_DIR to flat
    }

    private fun requestText(url: String, maxBytes: Int): String? {
        if (!extensionsEnabled || !LyricsExtensionSecurity.isHttpsUrl(url)) return null
        val request = Request.Builder().url(url).cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
        val call = Http.client.newCall(request)
        activeSyncCalls.add(call)
        return try {
            if (!extensionsEnabled) {
                call.cancel()
                null
            } else {
                call.execute().use { response ->
                    if (response.isSuccessful && response.request.url.isHttps) {
                        readBoundedBody(response.body, maxBytes)
                    } else null
                }
            }
        } finally {
            activeSyncCalls.remove(call)
        }
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

    private fun writeAtomically(target: File, value: String) {
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun validateManifest(manifest: LyricsExtensionManifest, expectedId: String) {
        require(LyricsExtensionSecurity.isSafeExtensionId(manifest.id) && manifest.id == expectedId) {
            "Extension ID does not match its directory"
        }
        require(manifest.name.length in 1..160 && manifest.version.length in 1..80)
        require(manifest.description.length <= 2_048 && manifest.author.length <= 160)
        require(manifest.defaultPriority in -1_000..1_000)
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote == local) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(rParts.size, lParts.size)) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    /**
     * Gets installed extension by ID.
     */
    fun getExtension(id: String): LyricsExtension? =
        if (extensionsEnabled && id !in com.music.bitchord.data.lyrics.BuiltinLyricsSources.nativeIds) {
            _installedExtensions.value.firstOrNull { it.id.equals(id, ignoreCase = true) }
        } else null

    private const val MAX_REGISTRY_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_SCRIPT_BYTES = 1024 * 1024
    private const val MAX_EXTENSION_COUNT = 100
    private const val SCRIPT_CHECKSUM_FILE = "index.js.sha256"
}
