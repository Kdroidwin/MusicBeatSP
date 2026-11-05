package com.music.bitchord.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.io.RandomAccessFile

/**
 * User-imported app font (.ttf / .otf).
 *
 * The picked file is copied into app-private storage, so it keeps working
 * after the original is moved or the picker's permission lapses. Each import
 * gets a fresh file name: Compose caches loaded fonts by file, and reusing one
 * name would keep showing the previous face.
 */
object CustomFont {
    private const val DIR = "fonts"

    /** Copies [uri] in, checks Android can load it, and makes it the app font. Returns the display name. */
    fun import(context: Context, uri: Uri): Result<String> = runCatching {
        val displayName = queryDisplayName(context, uri) ?: "custom font"
        val ext = displayName.substringAfterLast('.', "ttf").lowercase()
        require(ext == "ttf" || ext == "otf") { context.getString(R.string.font_error_type) }

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, "custom_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error(context.getString(R.string.font_error_open))

        val typeface = Typeface.Builder(target).build()
        if (typeface == null) {
            target.delete()
            error(context.getString(R.string.font_error_typeface))
        }

        // Only the newest import is kept.
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        AppSettings.setCustomFont(target.absolutePath, displayName)
        displayName
    }

    /** Back to the built-in face; removes the imported copy. */
    fun reset(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
        AppSettings.setCustomFont("", "")
    }

    /**
     * A family for the file at [path], or null if it is missing.
     *
     * A variable font (one with an `fvar` table, as many Japanese/Latin combo
     * fonts are) gets a real instance per weight. A static font is declared
     * once at Regular, so Compose synthesises bold for the heavier styles
     * instead of drawing every weight identically.
     */
    @OptIn(ExperimentalTextApi::class)
    fun familyFor(path: String): FontFamily? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            if (isVariableFont(file)) {
                FontFamily(
                    listOf(300, 400, 500, 600, 700, 800, 900).map { w ->
                        Font(
                            file = file,
                            weight = FontWeight(w),
                            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                        )
                    },
                )
            } else {
                FontFamily(Font(file = file, weight = FontWeight.Normal))
            }
        }.getOrNull()
    }

    /** Reads the sfnt table directory and looks for `fvar`. */
    private fun isVariableFont(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            val numTables = raf.readUnsignedShort()
            if (numTables !in 1..512) return@use false
            for (i in 0 until numTables) {
                raf.seek(12L + i * 16L)
                val tag = ByteArray(4).also { raf.readFully(it) }
                if (String(tag, Charsets.US_ASCII) == "fvar") return@use true
            }
            false
        }
    }.getOrDefault(false)

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
