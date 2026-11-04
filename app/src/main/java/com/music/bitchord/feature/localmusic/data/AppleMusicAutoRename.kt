package com.music.bitchord.feature.localmusic.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import com.kyant.taglib.TagLib
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class AppleCatalogTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val disc: Int,
    val track: Int,
)

data class AppleCatalogAlbum(val title: String, val artist: String, val tracks: List<AppleCatalogTrack>)

data class AutoRenameRow(
    val treeUri: Uri,
    val uri: Uri,
    val documentId: String,
    val parentId: String,
    val path: String,
    val oldName: String,
    val prefix: String,
    val track: AppleCatalogTrack?,
    val newName: String?,
    val sourceDiscNumber: Int? = null,
    val sourceTrackNumber: Int? = null,
    val issue: String? = null,
    val conflict: String? = null,
    val selected: Boolean = track != null && conflict == null,
)

data class AutoRenameResult(val changed: Int, val failed: Int, val message: String? = null)

/** Apple Music title/artist tagging and filename rename with preview and one-step undo. */
object AppleMusicAutoRename {
    private val leadingNumbers = Regex("^(\\d+(?:\\s*[-_.]\\s*\\d+)*(?:\\s*[-_.]\\s*|\\s+)?)")
    private val numbers = Regex("^(\\d+)(?:\\s*[-_.]\\s*(\\d+))?")
    private val discDirectory = Regex("(?i)(?:^|[^A-Za-z])(?:disk|disc|cd|ディスク)[\\s._-]*0*(\\d+)(?:$|[^0-9])")
    private val audioExtensions = setOf("flac", "mp3", "m4a", "m4b", "aac", "alac", "wav", "aif", "aiff", "ogg", "opus", "wma", "ape", "wv")

    fun fetchAlbum(albumUrl: String): AppleCatalogAlbum {
        val uri = Uri.parse(albumUrl.trim())
        require(uri.scheme in setOf("http", "https") && uri.host.equals("music.apple.com", true)) {
            "Apple MusicのアルバムURLを入力してください。"
        }
        val segments = uri.pathSegments
        val albumIndex = segments.indexOf("album")
        require(albumIndex >= 0) { "Apple MusicのアルバムURLを読み取れません。" }
        val collectionId = segments.drop(albumIndex + 1)
            .lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: uri.getQueryParameter("id")
            ?: error("アルバムIDがURLにありません。")
        val country = segments.firstOrNull()?.takeIf { it.matches(Regex("[a-zA-Z]{2}")) } ?: "jp"
        val request = Request.Builder()
            .url("https://itunes.apple.com/lookup?id=$collectionId&entity=song&country=$country&limit=200")
            .header("Accept", "application/json")
            .header("User-Agent", "MusicBeat/2.9")
            .build()
        val responseText = Http.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Apple Musicの曲情報を取得できませんでした (${response.code})" }
            Http.readBoundedUtf8(response.body, 2 * 1024 * 1024) ?: error("Apple Musicから応答がありません。")
        }
        val results = JSONObject(responseText).optJSONArray("results") ?: error("曲情報を読み取れませんでした。")
        var albumTitle = ""
        var albumArtist = ""
        val tracks = buildList {
            for (index in 0 until results.length()) {
                val row = results.optJSONObject(index) ?: continue
                if (row.optString("wrapperType") == "collection") {
                    albumTitle = row.optString("collectionName")
                    albumArtist = row.optString("artistName")
                }
                if (row.optString("wrapperType") != "track" || row.optString("kind") != "song") continue
                val title = row.optString("trackName").trim()
                val artist = row.optString("artistName").trim()
                if (title.isBlank() || artist.isBlank()) continue
                add(AppleCatalogTrack(
                    id = row.optLong("trackId"),
                    title = title,
                    artist = artist,
                    disc = row.optInt("discNumber", 1).coerceAtLeast(1),
                    track = row.optInt("trackNumber", 0),
                ))
            }
        }.sortedWith(compareBy<AppleCatalogTrack> { it.disc }.thenBy { it.track })
        require(tracks.isNotEmpty()) { "Apple Musicにアルバムの曲が見つかりません。" }
        return AppleCatalogAlbum(albumTitle, albumArtist, tracks)
    }

    fun preview(resolver: ContentResolver, treeUri: Uri, tracks: List<AppleCatalogTrack>): List<AutoRenameRow> {
        val files = mutableListOf<FileRow>()
        val names = linkedMapOf<String, MutableSet<String>>()
        val visited = hashSetOf<String>()
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        fun walk(parentId: String, path: List<String>, inheritedDisc: Int?) {
            if (!visited.add(parentId)) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val children: List<Child> = resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList<Child> {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        add(Child(id, name, cursor.getString(typeColumn)))
                    }
                }
            } ?: error("選択したフォルダを読み取れませんでした。")
            val inDirectory = names.getOrPut(parentId) { linkedSetOf() }
            children.forEach { child ->
                inDirectory += child.name
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val disc = discDirectory.find(child.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: inheritedDisc
                    walk(child.id, path + child.name, disc)
                    return@forEach
                }
                val extension = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension !in audioExtensions && child.mime?.startsWith("audio/") != true) return@forEach
                val prefix = leadingNumbers.find(child.name)?.value.orEmpty()
                val parsedNumber = parseFilenameNumber(child.name)
                files += FileRow(
                    id = child.id,
                    parent = parentId,
                    path = (path + child.name).joinToString("/"),
                    name = child.name,
                    prefix = prefix,
                    disc = inheritedDisc ?: parsedNumber?.disc,
                    number = parsedNumber?.number,
                    extension = child.name.substringAfterLast('.', "").let { ".$it" },
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id),
                )
            }
        }
        walk(treeId, emptyList(), null)
        return buildPreview(treeUri, files, names, tracks)
    }

    /** Preview the indexed songs under the folder currently open in FolderBrowserScreen. */
    fun preview(resolver: ContentResolver, songs: List<Song>, tracks: List<AppleCatalogTrack>): List<AutoRenameRow> {
        val files = mutableListOf<FileRow>()
        val names = linkedMapOf<String, MutableSet<String>>()
        songs.distinctBy { it.localUri ?: it.localPath ?: it.videoId }.forEach { song ->
            val filePath = song.localPath?.takeIf(String::isNotBlank)
            val uri = song.localUri?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: filePath?.let { Uri.fromFile(File(it)) }
                ?: return@forEach
            val name = filePath?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                ?: displayName(resolver, uri)?.takeIf(String::isNotBlank)
                ?: return@forEach
            val parent = song.localFolderPath?.takeIf(String::isNotBlank)
                ?: filePath?.substringBeforeLast('/', "")?.takeIf(String::isNotBlank)
                ?: return@forEach
            val prefix = leadingNumbers.find(name)?.value.orEmpty()
            val path = filePath ?: "$parent/$name"
            val parentNames = names.getOrPut(parent) { linkedSetOf() }
            parentNames += name
            val filenameNumber = parseFilenameNumber(name)
            val taggedNumber = parseMediaStoreTrackNumber(song.trackNumber)
            val directoryDisc = discDirectory.find(parent)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val filenameDisc = filenameNumber?.disc
            val explicitDisc = directoryDisc ?: filenameDisc
            val numberIssue = when {
                filenameNumber?.number != null && taggedNumber?.number != null && filenameNumber.number != taggedNumber.number ->
                    "ファイル名と音源タグの曲番号が一致しません"
                directoryDisc != null && filenameDisc != null && directoryDisc != filenameDisc ->
                    "フォルダ名とファイル名のディスク番号が一致しません"
                explicitDisc != null && taggedNumber?.disc != null && explicitDisc != taggedNumber.disc ->
                    "ファイル名またはフォルダ名と音源タグのディスク番号が一致しません"
                else -> null
            }
            val disc = explicitDisc ?: taggedNumber?.disc
            val trackNumber = filenameNumber?.number ?: taggedNumber?.number
            files += FileRow(
                id = uri.toString(),
                parent = parent,
                path = path,
                name = name,
                prefix = prefix,
                disc = disc,
                number = trackNumber,
                extension = name.substringAfterLast('.', "").let { ".$it" },
                uri = uri,
                issue = numberIssue,
            )
        }
        return buildPreview(Uri.EMPTY, files, names, tracks)
    }

    private fun buildPreview(
        treeUri: Uri,
        files: List<FileRow>,
        names: Map<String, Set<String>>,
        tracks: List<AppleCatalogTrack>,
    ): List<AutoRenameRow> {
        val orderedFiles = files.sortedWith(
            compareBy<FileRow> { it.disc ?: 1 }
                .thenBy { it.number ?: Int.MAX_VALUE }
                .thenComparator { left, right -> naturalCompare(left.path, right.path) },
        )
        val orderedTracks = tracks.sortedWith(compareBy<AppleCatalogTrack> { it.disc }.thenBy { it.track })
        val assigned = linkedMapOf<String, AppleCatalogTrack>()
        val issues = hashMapOf<String, String>().apply {
            orderedFiles.forEach { file -> file.issue?.let { put(file.id, it) } }
        }
        val flattenedCatalog = orderedTracks.map(AppleCatalogTrack::disc).distinct().size == 1
        val multipleFileDiscs = orderedFiles.mapNotNull(FileRow::disc).distinct().size > 1
        if (flattenedCatalog && multipleFileDiscs) {
            val continuousTracks = orderedTracks.map(AppleCatalogTrack::track) == (1..orderedTracks.size).toList()
            val continuousFiles = orderedFiles.all { it.disc != null && it.number != null } &&
                orderedFiles.groupBy(FileRow::disc).values.all { disk -> disk.mapNotNull(FileRow::number).sorted() == (1..disk.size).toList() }
            if (orderedFiles.size == orderedTracks.size && continuousTracks && continuousFiles && orderedFiles.all { it.issue == null }) {
                orderedFiles.zip(orderedTracks).forEach { (file, track) -> assigned[file.id] = track }
            } else orderedFiles.forEach { file ->
                if (file.issue == null) issues[file.id] = "ディスクごとの曲数または連番が揃っていません"
            }
        } else {
            orderedFiles.forEach { file ->
                if (file.issue != null) return@forEach
                val number = file.number
                if (number == null) {
                    issues[file.id] = "ファイル名から曲番号を読み取れません"
                    return@forEach
                }
                val candidates = orderedTracks.filter { it.track == number && (file.disc == null || it.disc == file.disc) }
                when {
                    candidates.size > 1 -> issues[file.id] = "複数ディスクに同じ曲番号があり、ディスク番号が必要です"
                    candidates.isEmpty() -> issues[file.id] = "曲番号に対応するApple Musicの曲がありません"
                    assigned.values.any { it.id == candidates.single().id } -> issues[file.id] = "曲番号が重複しています"
                    else -> assigned[file.id] = candidates.single()
                }
            }
        }
        val proposed = hashMapOf<Pair<String, String>, Int>()
        val initial = orderedFiles.map { file ->
            val track = assigned[file.id]
            val name = track?.let { file.prefix + sanitize(it.title) + file.extension }
            if (name != null) {
                val key = file.parent to name.lowercase(Locale.ROOT)
                proposed[key] = (proposed[key] ?: 0) + 1
            }
            AutoRenameRow(
                treeUri = treeUri,
                uri = file.uri,
                documentId = file.id,
                parentId = file.parent,
                path = file.path,
                oldName = file.name,
                prefix = file.prefix,
                track = track,
                newName = name,
                sourceDiscNumber = file.disc,
                sourceTrackNumber = file.number,
                issue = issues[file.id],
            )
        }
        return initial.map { row ->
            val name = row.newName ?: return@map row
            val key = row.parentId to name.lowercase(Locale.ROOT)
            val existing = names[row.parentId].orEmpty().any {
                it.equals(name, true) && !it.equals(row.oldName, true)
            }
            val conflict = when {
                (proposed[key] ?: 0) > 1 -> "変更後のファイル名が重複します"
                existing -> "同じ名前のファイルが既にあります"
                else -> null
            }
            row.copy(conflict = conflict, selected = conflict == null)
        }
    }

    suspend fun apply(context: Context, rows: List<AutoRenameRow>): AutoRenameResult = withContext(Dispatchers.IO) {
        if (rows.any { it.track == null }) return@withContext AutoRenameResult(0, 0, "曲番号で照合できないファイルがあります。プレビューを確認してください。")
        val selected = rows.filter { it.selected && it.track != null && it.newName != null && it.conflict == null }
        if (selected.isEmpty()) return@withContext AutoRenameResult(0, 0, "更新できるファイルがありません。")
        val pending = runCatching {
            selected.map { row ->
                UndoEntry(
                    treeUri = row.treeUri.toString(),
                    parentId = row.parentId,
                    originalUri = row.uri.toString(),
                    currentUri = row.uri.toString(),
                    originalName = row.oldName,
                    targetName = requireNotNull(row.newName),
                    tags = readTags(context, row.uri) ?: error("${row.path}: タグを読み取れませんでした"),
                )
            }
        }.getOrElse { return@withContext AutoRenameResult(0, selected.size, it.message ?: "タグのバックアップに失敗しました。") }
        runCatching { UndoJournal(context).save(pending) }
            .getOrElse { return@withContext AutoRenameResult(0, selected.size, "Undo情報を保存できません: ${it.message}") }

        var changed = 0
        var failed = 0
        var firstFailure: String? = null
        val completed = mutableListOf<UndoEntry>()
        selected.forEachIndexed { index, row ->
            val oldTags = pending[index].tags
            var currentUri = row.uri
            try {
                val track = requireNotNull(row.track)
                val updated = oldTags.toMutableMap().apply {
                    keys.filter { it.equals("TITLE", true) || it.equals("ARTIST", true) }.toList().forEach(::remove)
                    put("TITLE", listOf(track.title)); put("ARTIST", listOf(track.artist))
                }
                require(writeTags(context, row.uri, updated)) { "タグを書き込めませんでした" }
                val verified = readTags(context, row.uri).orEmpty()
                require(tagValue(verified, "TITLE") == track.title && tagValue(verified, "ARTIST") == track.artist) {
                    "書き込んだタイトル・アーティストを確認できませんでした"
                }
                if (row.newName != row.oldName) {
                    currentUri = renameAudio(context, row.uri, requireNotNull(row.newName))
                        ?: error("ファイル名を変更できませんでした")
                    val actualName = displayName(context, currentUri)
                    if (actualName != row.newName) {
                        val rollback = runCatching { renameAudio(context, currentUri, row.oldName) }.getOrNull()
                        if (rollback != null) currentUri = rollback
                        error("保存先がファイル名を変更しました${if (rollback != null) "。元の名前に戻しました" else "。元の名前には戻せませんでした"}")
                    }
                }
                completed += pending[index].copy(currentUri = currentUri.toString())
                UndoJournal(context).save(completed)
                changed++
            } catch (error: Exception) {
                runCatching {
                    val currentName = displayName(context, currentUri)
                    if (currentName != null && currentName != row.oldName) {
                        currentUri = renameAudio(context, currentUri, row.oldName) ?: currentUri
                    }
                    writeTags(context, currentUri, oldTags)
                }
                failed++
                if (firstFailure == null) firstFailure = "${row.path}: ${error.localizedMessage ?: "更新できませんでした"}"
            }
        }
        if (completed.isEmpty()) UndoJournal(context).clear()
        AutoRenameResult(changed, failed, firstFailure)
    }

    fun hasUndo(context: Context): Boolean = UndoJournal(context).load().isNotEmpty()

    suspend fun undo(context: Context): AutoRenameResult = withContext(Dispatchers.IO) {
        val journal = UndoJournal(context)
        val entries = runCatching { journal.load() }.getOrElse { return@withContext AutoRenameResult(0, 1, "Undo情報を読み取れません: ${it.message}") }
        if (entries.isEmpty()) return@withContext AutoRenameResult(0, 1, "元に戻せる更新がありません。")
        val remaining = entries.toMutableList()
        var restored = 0
        var failed = 0
        var firstFailure: String? = null
        entries.asReversed().forEach { entry ->
            try {
                var uri = resolveUndoUri(context.contentResolver, entry)
                    ?: error("対象ファイルが見つかりません。")
                require(writeTags(context, uri, entry.tags)) { "元のタグに戻せませんでした" }
                val existingName = displayName(context, uri)
                if (existingName != entry.originalName) {
                    uri = renameAudio(context, uri, entry.originalName)
                        ?: error("元のファイル名に戻せませんでした")
                }
                remaining.remove(entry)
                if (remaining.isEmpty()) journal.clear() else journal.save(remaining)
                restored++
            } catch (error: Exception) {
                failed++
                if (firstFailure == null) firstFailure = "${entry.originalName}: ${error.localizedMessage ?: "復元できませんでした"}"
            }
        }
        AutoRenameResult(restored, failed, firstFailure)
    }

    private fun readTags(context: Context, uri: Uri): Map<String, List<String>>? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), readPictures = false) ?: return@use null
            metadata.propertyMap.mapValues { (_, value) -> value.toList() }
        }
    }.getOrNull()

    private fun writeTags(context: Context, uri: Uri, values: Map<String, List<String>>): Boolean = runCatching {
        val properties = HashMap<String, Array<String>>().apply { values.forEach { (key, value) -> put(key, value.toTypedArray()) } }
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            TagLib.savePropertyMap(descriptor.dup().detachFd(), properties)
        } ?: false
    }.getOrDefault(false)

    private fun renameAudio(context: Context, uri: Uri, name: String): Uri? {
        return when {
            uri.scheme == "file" -> {
            val source = uri.path?.let(::File) ?: return null
            val parent = source.parentFile ?: return null
            val target = File(parent, name)
            if (target.exists() && target != source) return null
            if (source.renameTo(target)) Uri.fromFile(target) else null
            }
            uri.authority == MediaStore.AUTHORITY -> {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
                uri.takeIf { context.contentResolver.update(uri, values, null, null) > 0 }
            }
            else -> DocumentsContract.renameDocument(context.contentResolver, uri, name)
        }
    }

    private fun tagValue(tags: Map<String, List<String>>, key: String): String? =
        tags.entries.firstOrNull { it.key.equals(key, true) }?.value?.firstOrNull()

    private fun displayName(context: Context, uri: Uri): String? = displayName(context.contentResolver, uri)

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path?.let(::File)?.name
        else -> resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun resolveUndoUri(resolver: ContentResolver, entry: UndoEntry): Uri? {
        sequenceOf(entry.currentUri, entry.originalUri).map(Uri::parse).forEach { uri ->
            if (runCatching { displayName(resolver, uri) }.getOrNull() != null) return uri
        }
        val tree = Uri.parse(entry.treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, entry.parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(name) == entry.targetName || cursor.getString(name) == entry.originalName) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(id))
                    }
                }
            }
        return null
    }

    private fun sanitize(value: String): String = value.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), " ")
        .trim().trimEnd('.', ' ').ifEmpty { "untitled" }

    private fun parseFilenameNumber(name: String): TrackNumber? {
        val prefix = leadingNumbers.find(name)?.value ?: return null
        val match = numbers.find(prefix) ?: return null
        val first = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val second = match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)?.toIntOrNull()
        return if (second == null) TrackNumber(disc = null, number = first)
        else TrackNumber(disc = first, number = second)
    }

    /** MediaStore encodes disc and track as 1xxx / 2xxx, or high16/low16 on old Android versions. */
    private fun parseMediaStoreTrackNumber(rawValue: Int?): TrackNumber? {
        val raw = rawValue?.takeIf { it > 0 }?.toLong() ?: return null
        val disc: Int?
        val number: Int
        when {
            raw >= 65_536L -> {
                disc = (raw ushr 16).toInt()
                number = (raw and 0xFFFFL).toInt()
            }
            raw >= 1_000L -> {
                disc = (raw / 1_000L).toInt()
                number = (raw % 1_000L).toInt()
            }
            else -> {
                disc = null
                number = raw.toInt()
            }
        }
        return number.takeIf { it > 0 }?.let { TrackNumber(disc?.takeIf { value -> value > 0 }, it) }
    }

    private fun naturalCompare(left: String, right: String): Int {
        val a = Regex("\\d+|\\D+").findAll(left.lowercase(Locale.ROOT)).map { it.value }.toList()
        val b = Regex("\\d+|\\D+").findAll(right.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (index in 0 until minOf(a.size, b.size)) {
            val x = a[index]; val y = b[index]
            val compared = if (x.firstOrNull()?.isDigit() == true && y.firstOrNull()?.isDigit() == true) {
                (x.toLongOrNull() ?: Long.MAX_VALUE).compareTo(y.toLongOrNull() ?: Long.MAX_VALUE)
            } else x.compareTo(y)
            if (compared != 0) return compared
        }
        return a.size.compareTo(b.size)
    }

    private data class Child(val id: String, val name: String, val mime: String?)
    private data class TrackNumber(val disc: Int?, val number: Int)
    private data class FileRow(
        val id: String, val parent: String, val path: String, val name: String,
        val prefix: String, val disc: Int?, val number: Int?, val extension: String,
        val uri: Uri,
        val issue: String? = null,
    )

    private data class UndoEntry(
        val treeUri: String,
        val parentId: String,
        val originalUri: String,
        val currentUri: String,
        val originalName: String,
        val targetName: String,
        val tags: Map<String, List<String>>,
    )

    private class UndoJournal(context: Context) {
        private val file = AtomicFile(File(context.filesDir, "apple-music-auto-rename-undo.json"))
        fun load(): List<UndoEntry> = runCatching {
            if (!file.baseFile.exists()) return@runCatching emptyList()
            val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val rows = root.optJSONArray("entries") ?: return@runCatching emptyList()
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val tagsObject = row.getJSONObject("tags")
                val tags = buildMap {
                    val keys = tagsObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val values = tagsObject.getJSONArray(key)
                        put(key, List(values.length()) { values.getString(it) })
                    }
                }
                UndoEntry(
                    treeUri = row.getString("treeUri"), parentId = row.getString("parentId"),
                    originalUri = row.getString("originalUri"), currentUri = row.getString("currentUri"),
                    originalName = row.getString("originalName"), targetName = row.getString("targetName"), tags = tags,
                )
            }
        }.getOrDefault(emptyList())

        fun save(entries: List<UndoEntry>) {
            val root = JSONObject().put("entries", JSONArray().apply {
                entries.forEach { entry ->
                    val tagJson = JSONObject()
                    entry.tags.forEach { (key, values) -> tagJson.put(key, JSONArray(values)) }
                    put(JSONObject()
                        .put("treeUri", entry.treeUri).put("parentId", entry.parentId)
                        .put("originalUri", entry.originalUri).put("currentUri", entry.currentUri)
                        .put("originalName", entry.originalName).put("targetName", entry.targetName)
                        .put("tags", tagJson))
                }
            })
            val output = file.startWrite()
            try {
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }

        fun clear() { file.delete() }
    }
}
