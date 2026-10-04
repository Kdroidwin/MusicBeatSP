package com.music.bitchord.feature.localsearch.domain

import com.music.bitchord.data.model.Song
import java.io.File

/** Numeric-aware filename ordering for folder drill-downs (1, 2, …, 10). */
object LocalFileNameOrdering {
    fun sort(songs: List<Song>): List<Song> = songs.sortedWith { left, right ->
        val leftName = left.localPath?.let(::File)?.name.orEmpty()
        val rightName = right.localPath?.let(::File)?.name.orEmpty()
        naturalCompare(leftName, rightName)
            .takeIf { it != 0 }
            ?: left.title.compareTo(right.title, ignoreCase = true)
    }

    fun naturalCompare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val leftDigit = left[i].isDigit()
            val rightDigit = right[j].isDigit()
            if (leftDigit && rightDigit) {
                val leftStart = i
                val rightStart = j
                while (i < left.length && left[i] == '0') i++
                while (j < right.length && right[j] == '0') j++
                val leftSignificantStart = i
                val rightSignificantStart = j
                while (i < left.length && left[i].isDigit()) i++
                while (j < right.length && right[j].isDigit()) j++
                val leftDigits = i - leftSignificantStart
                val rightDigits = j - rightSignificantStart
                if (leftDigits != rightDigits) return leftDigits.compareTo(rightDigits)
                for (offset in 0 until leftDigits) {
                    val difference = left[leftSignificantStart + offset].compareTo(right[rightSignificantStart + offset])
                    if (difference != 0) return difference
                }
                val leftRun = i - leftStart
                val rightRun = j - rightStart
                if (leftRun != rightRun) return leftRun.compareTo(rightRun)
            } else {
                val difference = left[i].lowercaseChar().compareTo(right[j].lowercaseChar())
                if (difference != 0) return difference
                i++
                j++
            }
        }
        return (left.length - i).compareTo(right.length - j)
    }
}
