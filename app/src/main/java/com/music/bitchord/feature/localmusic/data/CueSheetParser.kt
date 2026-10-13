package com.music.bitchord.feature.localmusic.data


/** A playable audio track described by a CUE sheet. Times are in milliseconds. */
data class CueTrack(
    val number: Int,
    val file: String,
    val title: String?,
    val performer: String?,
    val startMs: Long,
)

data class ParsedCueSheet(
    val title: String?,
    val performer: String?,
    val tracks: List<CueTrack>,
)

/** Small, permissive CUE parser. It ignores non-audio tracks and unknown commands. */
object CueSheetParser {
    fun parse(text: String): ParsedCueSheet {
        require(text.isNotBlank()) { "CUE sheet is empty" }
        val lines = text.removePrefix("\uFEFF").lineSequence()
        var albumTitle: String? = null
        var albumPerformer: String? = null
        var currentFile: String? = null
        var currentTrack: MutableTrack? = null
        val result = mutableListOf<CueTrack>()

        fun finishTrack() {
            val track = currentTrack ?: return
            val startMs = track.startMs
            if (track.audio && track.file.isNotBlank() && startMs != null) {
                result += CueTrack(
                    number = track.number,
                    file = track.file,
                    title = track.title?.takeIf(String::isNotBlank),
                    performer = track.performer?.takeIf(String::isNotBlank),
                    startMs = startMs,
                )
            }
            currentTrack = null
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val command = line.substringBefore(' ').uppercase()
            val arg = line.substringAfter(' ', "").trim()
            when (command) {
                "FILE" -> currentFile = firstCueValue(arg).takeIf(String::isNotBlank)
                "TRACK" -> {
                    finishTrack()
                    val pieces = arg.split(Regex("\\s+"), limit = 2)
                    val number = pieces.firstOrNull()?.toIntOrNull() ?: continue
                    currentTrack = MutableTrack(number, currentFile.orEmpty(),
                        audio = pieces.getOrNull(1)?.equals("AUDIO", ignoreCase = true) == true)
                }
                "TITLE" -> {
                    val value = firstCueValue(arg).takeIf(String::isNotBlank) ?: continue
                    if (currentTrack == null) albumTitle = value else currentTrack?.title = value
                }
                "PERFORMER" -> {
                    val value = firstCueValue(arg).takeIf(String::isNotBlank) ?: continue
                    if (currentTrack == null) albumPerformer = value else currentTrack?.performer = value
                }
                "INDEX" -> {
                    val pieces = arg.split(Regex("\\s+"), limit = 2)
                    if (pieces.firstOrNull() == "01") {
                        currentTrack?.startMs = pieces.getOrNull(1)?.let(::parseTime)
                    }
                }
            }
        }
        finishTrack()
        require(result.isNotEmpty()) { "CUE sheet has no playable audio tracks" }
        return ParsedCueSheet(albumTitle, albumPerformer, result)
    }

    /** Converts CUE's mm:ss:ff clock (75 frames per second) into milliseconds. */
    fun parseTime(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val seconds = parts[1].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        val frames = parts[2].toLongOrNull()?.takeIf { it in 0..74 } ?: return null
        return (minutes * 60 + seconds) * 1_000 + (frames * 1_000 / 75)
    }

    private fun firstCueValue(raw: String): String {
        val value = raw.trim()
        if (value.startsWith('"')) {
            val closing = value.indexOf('"', startIndex = 1)
            if (closing > 0) return value.substring(1, closing)
        }
        return value.substringBefore(' ')
    }

    private data class MutableTrack(
        val number: Int,
        val file: String,
        val audio: Boolean,
        var title: String? = null,
        var performer: String? = null,
        var startMs: Long? = null,
    )
}
