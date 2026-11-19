package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class PeriodicBackupKind(val wireValue: String) {
    EXPORT("export"),
    IMPORT("import"),
}

internal enum class PeriodicBackupFileMode(val storedValue: String) {
    OVERWRITE("overwrite"),
    KEEP_ALL("keep_all"),
    KEEP_LIMIT("keep_limit"),
}

internal data class PeriodicBackupConfig(
    val exportEnabled: Boolean = false,
    val importEnabled: Boolean = false,
    val exportFolder: String? = null,
    val importFolder: String? = null,
    val exportIntervalMinutes: Int = 1440,
    val importIntervalMinutes: Int = 1440,
    val fileMode: PeriodicBackupFileMode = PeriodicBackupFileMode.OVERWRITE,
    val keepFileCount: Int = 10,
    val lastExportStatus: String? = null,
    val lastImportStatus: String? = null,
    val lastImportedFingerprint: String? = null,
    val lastExportedFingerprint: String? = null,
)

/** Settings deliberately live outside AppSettings so an imported backup cannot disable sync. */
internal object PeriodicBackupStore {
    private const val PREFS = "periodic_backup_settings"

    fun load(context: Context): PeriodicBackupConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PeriodicBackupConfig(
            exportEnabled = prefs.getBoolean("export_enabled", false),
            importEnabled = prefs.getBoolean("import_enabled", false),
            exportFolder = prefs.getString("export_folder", null),
            importFolder = prefs.getString("import_folder", null),
            exportIntervalMinutes = prefs.getInt("export_interval", 1440).validatedInterval(),
            importIntervalMinutes = prefs.getInt("import_interval", 1440).validatedInterval(),
            fileMode = PeriodicBackupFileMode.entries.firstOrNull {
                it.storedValue == prefs.getString("file_mode", null)
            } ?: PeriodicBackupFileMode.OVERWRITE,
            keepFileCount = prefs.getInt("keep_count", 10).coerceIn(1, 100),
            lastExportStatus = prefs.getString("last_export_status", null),
            lastImportStatus = prefs.getString("last_import_status", null),
            lastImportedFingerprint = prefs.getString("last_import_fingerprint", null),
            lastExportedFingerprint = prefs.getString("last_export_fingerprint", null),
        )
    }

    fun update(context: Context, transform: (PeriodicBackupConfig) -> PeriodicBackupConfig) {
        val updated = transform(load(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("export_enabled", updated.exportEnabled)
            .putBoolean("import_enabled", updated.importEnabled)
            .putOptionalString("export_folder", updated.exportFolder)
            .putOptionalString("import_folder", updated.importFolder)
            .putInt("export_interval", updated.exportIntervalMinutes.validatedInterval())
            .putInt("import_interval", updated.importIntervalMinutes.validatedInterval())
            .putString("file_mode", updated.fileMode.storedValue)
            .putInt("keep_count", updated.keepFileCount.coerceIn(1, 100))
            .putOptionalString("last_export_status", updated.lastExportStatus)
            .putOptionalString("last_import_status", updated.lastImportStatus)
            .putOptionalString("last_import_fingerprint", updated.lastImportedFingerprint)
            .putOptionalString("last_export_fingerprint", updated.lastExportedFingerprint)
            .commit()
    }

    fun updateStatus(
        context: Context,
        kind: PeriodicBackupKind,
        status: String,
        fingerprint: String? = null,
    ) {
        update(context) { current ->
            when (kind) {
                PeriodicBackupKind.EXPORT -> current.copy(
                    lastExportStatus = status,
                    lastExportedFingerprint = fingerprint ?: current.lastExportedFingerprint,
                )
                PeriodicBackupKind.IMPORT -> current.copy(
                    lastImportStatus = status,
                    lastImportedFingerprint = fingerprint ?: current.lastImportedFingerprint,
                )
            }
        }
    }

    private fun Int.validatedInterval(): Int = PERIODS_MINUTES.firstOrNull { it == this } ?: 1440

    private fun android.content.SharedPreferences.Editor.putOptionalString(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)
}

/** Stores the periodic task's password with a Keystore AES key, outside device backup. */
internal object PeriodicBackupSecretStore {
    private const val KEY_ALIAS = "musicbeat.periodic.backup.password.v1"
    private const val FILE_NAME = "periodic-backup-password.bin"
    private const val IV_SIZE = 12

    @Synchronized
    fun save(context: Context, password: CharArray) {
        require(password.size >= 8) { "Use at least 8 characters for the backup password" }
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(password))
        val clear = ByteArray(encoded.remaining()).also(encoded::get)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(PASSWORD_AAD)
            val encrypted = cipher.doFinal(clear)
            val stored = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(encrypted)
                .array()
            try {
                val target = File(context.noBackupFilesDir, FILE_NAME)
                val temp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
                temp.writeBytes(stored)
                try {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    // Keep the existing password file intact if replacement
                    // fails; the same-filesystem fallback still replaces it.
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                stored.fill(0)
                encrypted.fill(0)
            }
        } finally {
            clear.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    @Synchronized
    fun read(context: Context): CharArray? {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (!file.isFile) return null
        val stored = file.readBytes()
        require(stored.size > 1 + IV_SIZE && stored[0].toInt() == IV_SIZE) { "Saved backup password is invalid" }
        val iv = stored.copyOfRange(1, 1 + IV_SIZE)
        val encrypted = stored.copyOfRange(1 + IV_SIZE, stored.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(PASSWORD_AAD)
        val clear = cipher.doFinal(encrypted)
        return try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(clear))
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            clear.fill(0)
            stored.fill(0)
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.noBackupFilesDir, FILE_NAME).delete()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun hasPassword(context: Context): Boolean =
        File(context.noBackupFilesDir, FILE_NAME).isFile

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private val PASSWORD_AAD = "musicbeat-periodic-backup-password-v1".toByteArray(StandardCharsets.UTF_8)
}

internal val PERIODS_MINUTES = listOf(5, 10, 30, 60, 360, 720, 1440, 10_080, 43_200)

internal object PeriodicBackupScheduler {
    private const val EXPORT_WORK = "musicbeat-periodic-backup-export-v1"
    private const val IMPORT_WORK = "musicbeat-periodic-backup-import-v1"
    private const val INPUT_KIND = "kind"

    fun reconcile(context: Context) {
        schedule(context, PeriodicBackupKind.EXPORT, replace = false)
        schedule(context, PeriodicBackupKind.IMPORT, replace = false)
    }

    fun schedule(context: Context, kind: PeriodicBackupKind, replace: Boolean = true) {
        val config = PeriodicBackupStore.load(context)
        val enabled = kind.isEnabled(config)
        val folder = kind.folder(config)
        if (!enabled || folder.isNullOrBlank() || !PeriodicBackupSecretStore.hasPassword(context)) {
            WorkManager.getInstance(context).cancelUniqueWork(kind.workName())
            return
        }
        val delay = kind.interval(config).toLong()
        val request = OneTimeWorkRequestBuilder<PeriodicBackupWorker>()
            .setInputData(Data.Builder().putString(INPUT_KIND, kind.wireValue).build())
            .setInitialDelay(delay, java.util.concurrent.TimeUnit.MINUTES)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(kind.workName(), policy, request)
    }

    internal fun scheduleNext(context: Context, kind: PeriodicBackupKind) {
        val config = PeriodicBackupStore.load(context)
        if (!kind.isEnabled(config) || kind.folder(config).isNullOrBlank() || !PeriodicBackupSecretStore.hasPassword(context)) return
        val request = OneTimeWorkRequestBuilder<PeriodicBackupWorker>()
            .setInputData(Data.Builder().putString(INPUT_KIND, kind.wireValue).build())
            .setInitialDelay(kind.interval(config).toLong(), java.util.concurrent.TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(kind.workName(), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun PeriodicBackupKind.isEnabled(config: PeriodicBackupConfig) =
        if (this == PeriodicBackupKind.EXPORT) config.exportEnabled else config.importEnabled

    private fun PeriodicBackupKind.folder(config: PeriodicBackupConfig) =
        if (this == PeriodicBackupKind.EXPORT) config.exportFolder else config.importFolder

    private fun PeriodicBackupKind.interval(config: PeriodicBackupConfig) =
        if (this == PeriodicBackupKind.EXPORT) config.exportIntervalMinutes else config.importIntervalMinutes

    private fun PeriodicBackupKind.workName() = if (this == PeriodicBackupKind.EXPORT) EXPORT_WORK else IMPORT_WORK
}

internal class PeriodicBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val kind = PeriodicBackupKind.entries.firstOrNull { it.wireValue == inputData.getString("kind") }
            ?: return Result.failure()
        if (!isConfigured(kind)) {
            PeriodicBackupStore.updateStatus(applicationContext, kind, "Not configured")
            return Result.success()
        }
        try {
            PeriodicBackupExecution.mutex.withLock {
                if (isStopped) return Result.success()
                when (kind) {
                    PeriodicBackupKind.EXPORT -> export()
                    PeriodicBackupKind.IMPORT -> importLatest()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PeriodicBackupStore.updateStatus(
                applicationContext,
                kind,
                "Failed: ${error.message ?: error.javaClass.simpleName}".take(500),
            )
        } finally {
            if (!isStopped) PeriodicBackupScheduler.scheduleNext(applicationContext, kind)
        }
        return Result.success()
    }

    private fun isConfigured(kind: PeriodicBackupKind): Boolean {
        val config = PeriodicBackupStore.load(applicationContext)
        val enabled = if (kind == PeriodicBackupKind.EXPORT) config.exportEnabled else config.importEnabled
        val folder = if (kind == PeriodicBackupKind.EXPORT) config.exportFolder else config.importFolder
        return enabled && !folder.isNullOrBlank() && PeriodicBackupSecretStore.hasPassword(applicationContext)
    }

    private suspend fun export() {
        val config = PeriodicBackupStore.load(applicationContext)
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(requireNotNull(config.exportFolder)))
            ?: error("The selected export folder is unavailable")
        require(root.isDirectory && root.canWrite()) { "The selected export folder is not writable" }
        val password = requireNotNull(PeriodicBackupSecretStore.read(applicationContext)) {
            "Set a backup password in periodic backup settings"
        }
        try {
            val name = if (config.fileMode == PeriodicBackupFileMode.OVERWRITE) {
                "musicbeat-backup-current.json"
            } else {
                "musicbeat-backup-${FILE_STAMP.format(Instant.now())}.json"
            }
            val stageName = "musicbeat-backup-${System.currentTimeMillis()}.pending"
            val stage = root.createFile("application/json", stageName)
                ?: error("The backup folder could not create files")
            var stateFingerprint: String? = null
            try {
                Backup.exportTo(applicationContext, stage.uri, password).getOrThrow()
                stateFingerprint = Backup.readStateFingerprint(applicationContext, stage.uri, password).getOrThrow()
                if (config.fileMode == PeriodicBackupFileMode.OVERWRITE) {
                    val old = root.findFile(name)
                    val previousName = "musicbeat-backup-previous.json"
                    val previous = root.findFile(previousName)
                    if (previous != null && !previous.delete()) error("Could not rotate the previous backup")
                    if (old != null && !old.renameTo(previousName)) {
                        error("This folder provider does not support safe backup replacement")
                    }
                    if (!stage.renameTo(name)) {
                        old?.let { root.findFile(previousName)?.renameTo(name) }
                        error("Could not finish replacing the backup; the prior file was preserved")
                    }
                    root.findFile(previousName)?.delete()
                } else if (!stage.renameTo(name)) {
                    error("Could not finalize the backup file")
                }
                if (config.fileMode == PeriodicBackupFileMode.KEEP_LIMIT) {
                    pruneBackups(root, config.keepFileCount)
                }
            } catch (error: Exception) {
                stage.takeIf { it.exists() && it.name?.endsWith(".pending") == true }?.delete()
                throw error
            }
            PeriodicBackupStore.updateStatus(
                applicationContext,
                PeriodicBackupKind.EXPORT,
                "Completed ${Instant.now()}",
                stateFingerprint,
            )
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun importLatest() {
        val config = PeriodicBackupStore.load(applicationContext)
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(requireNotNull(config.importFolder)))
            ?: error("The selected import folder is unavailable")
        require(root.isDirectory && root.canRead()) { "The selected import folder is not readable" }
        val latest = root.listFiles()
            .filter { doc ->
                val name = doc.name.orEmpty()
                doc.isFile && name.startsWith("musicbeat-backup-") && name.endsWith(".json", ignoreCase = true)
            }
            .maxWithOrNull(compareBy<DocumentFile> { it.lastModified() }.thenBy { it.name.orEmpty() })
            ?: error("No MusicBeat backup was found in the selected folder")
        val password = requireNotNull(PeriodicBackupSecretStore.read(applicationContext)) {
            "Set a backup password in periodic backup settings"
        }
        try {
            val fingerprint = Backup.readStateFingerprint(applicationContext, latest.uri, password).getOrThrow()
            val lastFingerprint = PeriodicBackupStore.load(applicationContext).lastImportedFingerprint
            if (fingerprint == lastFingerprint || fingerprint == config.lastExportedFingerprint) {
                PeriodicBackupStore.updateStatus(applicationContext, PeriodicBackupKind.IMPORT, "Already up to date")
                return
            }
            Backup.importFrom(applicationContext, latest.uri, password).getOrThrow()
            PeriodicBackupStore.updateStatus(
                applicationContext,
                PeriodicBackupKind.IMPORT,
                "Imported ${latest.name.orEmpty()} at ${Instant.now()}",
                fingerprint,
            )
        } finally {
            password.fill('\u0000')
        }
    }

    private fun pruneBackups(root: DocumentFile, maxFiles: Int) {
        val backups = root.listFiles()
            .filter { doc ->
                val name = doc.name.orEmpty()
                doc.isFile && name.startsWith("musicbeat-backup-") && name.endsWith(".json", ignoreCase = true)
            }
            .sortedWith(compareBy<DocumentFile> { it.lastModified() }.thenBy { it.name.orEmpty() })
        backups.take((backups.size - maxFiles).coerceAtLeast(0)).forEach { old -> old.delete() }
    }

    private companion object {
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS")
            .withZone(ZoneOffset.UTC)
    }
}

private object PeriodicBackupExecution {
    val mutex = Mutex()
}
