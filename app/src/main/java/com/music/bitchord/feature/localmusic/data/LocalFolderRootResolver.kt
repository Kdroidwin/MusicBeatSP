package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/** Resolves SAF storage-tree URIs to the MediaStore paths used by local songs. */
object LocalFolderRootResolver {
    fun resolve(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let(::normalizePath)
        if (uri.scheme != "content" || uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null

        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val volumeId = documentId.substringBefore(':').takeIf(String::isNotBlank) ?: return null
        val relativePath = documentId.substringAfter(':', "").trim('/')
        val storageRoot = when {
            volumeId.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            else -> findMountedVolumeRoot(context, volumeId)
                ?: volumeId.takeIf { SAFE_VOLUME_ID.matches(it) }?.let { "/storage/$it" }
        } ?: return null

        return normalizePath(if (relativePath.isEmpty()) storageRoot else "$storageRoot/$relativePath")
    }

    private fun findMountedVolumeRoot(context: Context, volumeId: String): String? = runCatching {
        context.getExternalFilesDirs(null)
            .asSequence()
            .filterNotNull()
            .mapNotNull { directory ->
                val path = normalizePath(directory.absolutePath) ?: return@mapNotNull null
                val marker = "/Android/data/"
                val markerIndex = path.indexOf(marker, ignoreCase = true)
                if (markerIndex <= 0) return@mapNotNull null
                val root = path.substring(0, markerIndex)
                root.takeIf { it.substringAfterLast('/').equals(volumeId, ignoreCase = true) }
            }
            .firstOrNull()
    }.getOrNull()

    private fun normalizePath(path: String): String? {
        val normalized = path.replace('\\', '/').replace(Regex("/{2,}"), "/").trimEnd('/').ifEmpty { "/" }
        if (!normalized.startsWith('/')) return null
        return normalized
    }

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private val SAFE_VOLUME_ID = Regex("[A-Za-z0-9_-]{1,64}")
}
