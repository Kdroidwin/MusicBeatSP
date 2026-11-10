package com.music.bitchord.ui.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.palette.graphics.Palette
import java.io.File
import java.io.FileOutputStream

/** Builds a clean share card directly, so system bars and app controls never enter the image. */
suspend fun shareNowPlayingPoster(context: Context, song: Song): Boolean = runCatching {
    val imageUrl = song.artworkAt(POSTER_ART_SIZE)
    val artwork = imageUrl?.let { url ->
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(POSTER_ART_SIZE)
            .allowHardware(false)
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }
    val poster = withContext(Dispatchers.Default) { renderPoster(context, song, artwork) }
    val sharedFile = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        File(directory, "now-playing.png").also { file ->
            FileOutputStream(file).use { stream ->
                check(poster.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sharedFile)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(
            Intent.EXTRA_TEXT,
            listOf(song.title, song.artist.takeIf(String::isNotBlank), "#MusicBeatSP")
                .filterNotNull()
                .joinToString("\n"),
        )
        clipData = ClipData.newUri(context.contentResolver, "Now Playing", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.share_now_playing)))
    true
}.getOrDefault(false)

private fun renderPoster(context: Context, song: Song, artwork: Bitmap?): Bitmap {
    val bitmap = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val accent = artwork?.let {
        runCatching {
            val palette = Palette.from(it).generate()
            palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb
        }.getOrNull()
    } ?: Color.rgb(77, 89, 125)
    val top = ColorUtils.blendARGB(accent, Color.BLACK, 0.58f)
    val bottom = ColorUtils.blendARGB(accent, Color.BLACK, 0.82f)
    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, POSTER_WIDTH.toFloat(), POSTER_HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, POSTER_WIDTH.toFloat(), POSTER_HEIGHT.toFloat(), backgroundPaint)
    // Soft accent pools add depth without sampling or blurring the entire cover.
    canvas.drawCircle(
        POSTER_WIDTH * 0.12f,
        POSTER_HEIGHT * 0.82f,
        POSTER_WIDTH * 0.66f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(accent, 36) },
    )

    val artworkRect = RectF(POSTER_MARGIN, 120f, POSTER_WIDTH - POSTER_MARGIN, 960f)
    if (artwork != null) {
        drawRoundedArtwork(canvas, artwork, artworkRect, 34f)
    } else {
        canvas.drawRoundRect(
            artworkRect,
            34f,
            34f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE.copyAlpha(32) },
        )
        val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE.copyAlpha(150)
            textSize = 150f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("♫", artworkRect.centerX(), artworkRect.centerY() + 52f, note)
    }

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 54f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    val titleLayout = StaticLayout.Builder.obtain(
        song.title.ifBlank { context.getString(R.string.lyrics_unknown_title) },
        0,
        song.title.ifBlank { context.getString(R.string.lyrics_unknown_title) }.length,
        titlePaint,
        (POSTER_WIDTH - POSTER_MARGIN * 2).toInt(),
    )
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setMaxLines(2)
        .setEllipsize(android.text.TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .build()
    canvas.save()
    canvas.translate(POSTER_MARGIN, 1018f)
    titleLayout.draw(canvas)
    canvas.restore()

    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE.copyAlpha(194)
        textSize = 38f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val artistLayout = StaticLayout.Builder.obtain(
        song.artist.ifBlank { context.getString(R.string.unknown_artist) },
        0,
        song.artist.ifBlank { context.getString(R.string.unknown_artist) }.length,
        artistPaint,
        (POSTER_WIDTH - POSTER_MARGIN * 2).toInt(),
    )
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setMaxLines(1)
        .setEllipsize(android.text.TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .build()
    canvas.save()
    canvas.translate(POSTER_MARGIN, 1168f)
    artistLayout.draw(canvas)
    canvas.restore()

    canvas.drawLine(
        POSTER_MARGIN,
        1280f,
        POSTER_WIDTH - POSTER_MARGIN,
        1280f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE.copyAlpha(64)
            strokeWidth = 2f
        },
    )
    canvas.drawText(
        "#MusicBeatSP",
        POSTER_MARGIN,
        1350f,
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        },
    )
    return bitmap
}

private fun drawRoundedArtwork(canvas: Canvas, artwork: Bitmap, destination: RectF, radius: Float) {
    val scale = maxOf(destination.width() / artwork.width, destination.height() / artwork.height)
    val width = artwork.width * scale
    val height = artwork.height * scale
    val left = destination.centerX() - width / 2f
    val top = destination.centerY() - height / 2f
    val shader = BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(left, top) })
    val clip = Path().apply { addRoundRect(destination, radius, radius, Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(clip)
    canvas.drawRect(destination, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    canvas.restore()
}

private fun Int.copyAlpha(alpha: Int): Int = ColorUtils.setAlphaComponent(this, alpha)

private const val POSTER_WIDTH = 1080
private const val POSTER_HEIGHT = 1440
private const val POSTER_MARGIN = 120f
private const val POSTER_ART_SIZE = 1200
