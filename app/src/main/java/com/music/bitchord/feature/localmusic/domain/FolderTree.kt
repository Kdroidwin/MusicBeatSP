package com.music.bitchord.feature.localmusic.domain

import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localsearch.domain.LocalFileNameOrdering

private fun normalizeFolderPath(path: String): String =
    path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }

/** A directory and the playable tracks in its subtree. */
data class FolderNode(
    val path: String,
    val name: String,
    val relativePath: String,
    val subfolders: List<FolderNode>,
    val directSongs: List<Song>,
    val totalSongCount: Int,
    val coverSongs: List<Song>,
) {
    val displayName: String
        get() = name.trim().takeIf(String::isNotBlank)
            ?: path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
                .takeIf(String::isNotBlank)
            ?: path

    val isLeaf: Boolean get() = subfolders.isEmpty()
    val allSongs: List<Song> by lazy(LazyThreadSafetyMode.NONE) {
        directSongs + subfolders.flatMap { it.allSongs }
    }

    private val pathIndex: Map<String, FolderNode> by lazy(LazyThreadSafetyMode.NONE) {
        buildMap {
            fun add(node: FolderNode) {
                put(normalizeFolderPath(node.path), node)
                node.subfolders.forEach(::add)
            }
            add(this@FolderNode)
        }
    }

    fun findByPath(targetPath: String): FolderNode? = pathIndex[normalizeFolderPath(targetPath)]
}

object FolderTreeBuilder {
    fun build(songs: List<Song>, customRoot: String? = null): FolderNode {
        val songsByDir = songs.asSequence()
            .mapNotNull { song ->
                val directory = song.localFolderPath?.takeIf(String::isNotBlank)
                    ?.let(::normalizeFolderPath)
                    ?: song.localPath?.takeIf(String::isNotBlank)
                        ?.let(::normalizeFolderPath)
                        ?.substringBeforeLast('/', "")
                    ?: return@mapNotNull null
                if (directory.isBlank()) null else directory to song
            }
            .groupBy({ it.first }, { it.second })

        val normalizedRoot = customRoot?.takeIf(String::isNotBlank)?.let(::normalizeFolderPath)
        val rootPath = normalizedRoot ?: findCommonRoot(songsByDir.keys)
        val rootName = rootPath.substringAfterLast('/').ifBlank { "Music" }
        val root = FolderNodeBuilder(rootPath, rootName)

        for ((directory, directorySongs) in songsByDir) {
            if (normalizedRoot != null && !isSameOrChildPath(directory, normalizedRoot)) continue
            val relative = relativeTo(directory, rootPath) ?: continue
            if (relative.isEmpty()) {
                root.directSongs.addAll(directorySongs)
                continue
            }

            var current = root
            var currentPath = rootPath
            for (segment in relative.split('/').filter(String::isNotEmpty)) {
                currentPath = if (currentPath == "/") "/$segment" else "$currentPath/$segment"
                current = current.subfolders.getOrPut(segment) {
                    FolderNodeBuilder(currentPath, segment)
                }
            }
            current.directSongs.addAll(directorySongs)
        }

        return root.build(rootPath)
    }

    private fun findCommonRoot(paths: Collection<String>): String {
        if (paths.isEmpty()) return "/"
        val segments = paths.map { normalizeFolderPath(it).split('/').filter(String::isNotEmpty) }
        val common = mutableListOf<String>()
        for (index in 0 until (segments.minOfOrNull { it.size } ?: 0)) {
            val segment = segments.first()[index]
            if (segments.all { it[index].equals(segment, ignoreCase = true) }) common += segment else break
        }
        return if (common.isEmpty()) "/" else "/${common.joinToString("/")}"
    }

    private fun relativeTo(path: String, root: String): String? {
        val normalizedPath = normalizeFolderPath(path)
        val normalizedRoot = normalizeFolderPath(root)
        if (!isSameOrChildPath(normalizedPath, normalizedRoot)) return null
        if (normalizedPath == normalizedRoot) return ""
        return normalizedPath.substring(if (normalizedRoot == "/") 1 else normalizedRoot.length + 1)
    }

    private fun isSameOrChildPath(path: String, root: String): Boolean {
        val normalizedPath = normalizeFolderPath(path)
        val normalizedRoot = normalizeFolderPath(root)
        return normalizedPath.equals(normalizedRoot, ignoreCase = true) ||
            normalizedPath.startsWith(if (normalizedRoot == "/") "/" else "$normalizedRoot/", ignoreCase = true)
    }

    private class FolderNodeBuilder(val path: String, val name: String) {
        val subfolders = mutableMapOf<String, FolderNodeBuilder>()
        val directSongs = mutableListOf<Song>()

        fun build(rootPath: String): FolderNode {
            val sortedSubfolders = subfolders.values
                .sortedWith { left, right -> LocalFileNameOrdering.naturalCompare(left.name, right.name) }
                .map { it.build(rootPath) }
            val sortedSongs = LocalFileNameOrdering.sort(directSongs)
            val childSongs = sortedSubfolders.flatMap { it.allSongs }
            val allSongs = sortedSongs + childSongs
            val coverSongs = allSongs.asSequence()
                .filter { !it.thumbnailUrl.isNullOrBlank() }
                .distinctBy { it.albumName?.trim()?.lowercase() ?: it.videoId }
                .take(4)
                .toList()
            val relative = when {
                normalizeFolderPath(path) == normalizeFolderPath(rootPath) -> ""
                normalizeFolderPath(rootPath) == "/" -> normalizeFolderPath(path).removePrefix("/")
                else -> normalizeFolderPath(path).removePrefix("${normalizeFolderPath(rootPath)}/")
            }
            return FolderNode(
                path = normalizeFolderPath(path),
                name = name,
                relativePath = relative,
                subfolders = sortedSubfolders,
                directSongs = sortedSongs,
                totalSongCount = allSongs.size,
                coverSongs = coverSongs,
            )
        }
    }
}
