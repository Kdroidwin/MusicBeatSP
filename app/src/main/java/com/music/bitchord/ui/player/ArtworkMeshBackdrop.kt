package com.music.bitchord.ui.player

import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.localmusic.coil.LocalAudioArtworkFetcher
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Reads the still artwork palette and its tiny backdrop mesh while the track
 * is already playing in the compact player. The expanded player uses the same
 * request keys, so opening it can paint the artwork colours on its first frame
 * instead of starting with the dark theme fallback and waiting for a decode.
 * Nothing in this path touches the media player or delays playback.
 */
@Composable
fun PlayerArtworkBackdropPreloader(
    song: Song?,
    enabled: Boolean,
) {
    if (song == null || !enabled) return

    val folderArtworkFallbackMode by AppSettings.folderArtworkFallbackMode.collectAsStateWithLifecycle()
    val artUrl = remember(song, folderArtworkFallbackMode) { song.artworkAt(PLAYER_ART_PX) }
    val localPreviewArtUrl = remember(song, artUrl) {
        artUrl?.takeIf { LocalAudioArtworkFetcher.isLocalAudioUri(Uri.parse(it)) }
            ?.let { song.artworkAt(480) }
    }
    val backdropArtworkUrl = localPreviewArtUrl ?: artUrl

    // Palette extraction samples 128px and the backdrop mesh samples 32px, so
    // warming the row-sized rendition is enough and avoids fetching the large
    // full-screen copy while a track is starting. Both caches key by artwork
    // identity rather than requested resolution, so the expanded player can
    // use these results without changing its sharp cover request.
    rememberArtworkPalette(
        imageUrl = artUrl,
        dark = true,
        artPx = ROW_ART_PX,
        animateOnLoad = false,
    )
    rememberArtworkMesh(backdropArtworkUrl, artPx = ROW_ART_PX)
}

/**
 * The artwork's own colours, upside down and reduced to a mesh, for the player
 * to stand on.
 *
 * [MeshGradientBackground] answers a different question: it asks the quantiser
 * what colours a sleeve is *about* and paints four blobs of them. That is why a
 * cover that is nine-tenths black with a red stripe came out as a red screen —
 * the quantiser reports red because red is the interesting answer, and nothing
 * downstream knows how little of the picture it was. It also has no idea *where*
 * in the frame that red was.
 *
 * This is the other approach: no quantiser at all. The sleeve is averaged into a
 * [MESH_GRID] square of means; the row against the seam is kept in place, and
 * everything below it is flipped and then rotated sideways — see
 * [rotatedBelowSeam]. What the backdrop holds is the cover's own colours,
 * roughly in the cover's own proportions (nine-tenths black stays nine-tenths
 * black) and with each cell's neighbours exactly what they were in the source,
 * but not lined up in the cover's own *position*: a flip on its own reads as a
 * reflection on any cover with real structure to it — a face, a horizon, a
 * logo — however coarse the grid, because the layout still lines up column for
 * column with what's on screen above it. The rotation is what actually breaks
 * that column-for-column match; the coarseness just keeps any one cell from
 * being recognisable on its own.
 *
 * Held as a tiny bitmap rather than a list of colours because that is exactly
 * what the hardware wants: one [DrawScope.drawImage] with bilinear filtering
 * interpolates the whole mesh in the sampler. The alternative — a blob per cell,
 * as the old backdrop draws — would be thirty-six full-screen radial gradients.
 */
@Immutable
class ArtworkMesh internal constructor(
    internal val image: ImageBitmap,
    private val bitmap: Bitmap,
) {
    /** Blur the small source texture before it is stretched over the window. */
    internal fun blurred(sigmaX: Float, sigmaY: Float): ArtworkMesh {
        if (sigmaX <= 0.05f && sigmaY <= 0.05f) return this
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val horizontal = blurPixels(pixels, width, height, sigmaX, horizontal = true)
        val output = blurPixels(horizontal, width, height, sigmaY, horizontal = false)
        val blurredBitmap = Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
        return ArtworkMesh(blurredBitmap.asImageBitmap(), blurredBitmap)
    }
}

/**
 * The mesh for the artwork at [imageUrl], or null until one has been read.
 *
 * [canvasFrame] — a frame grabbed off a playing motion cover — takes over when
 * one arrives, for the reason [rememberArtworkColors] describes: a clip is
 * frequently graded nothing like the still sleeve it replaces. A caller that
 * keeps sending fresh ones gets a backdrop that follows the clip; see
 * [CanvasArtworkPlayer]'s `frameCapturePx` for what one of them costs.
 */
@Composable
fun rememberArtworkMesh(
    imageUrl: String?,
    canvasFrame: Bitmap? = null,
    /**
     * The artwork size to read, which should be the one the caller already has
     * on screen. Nothing here survives being averaged into thirty-six cells, so
     * resolution buys nothing and a shared disk-cache entry buys everything —
     * ask for a size no one is showing and the backdrop sits flat until a
     * second copy of the same cover comes over the wire.
     */
    artPx: Int = CARD_ART_PX,
): ArtworkMesh? {
    val context = LocalContext.current
    val folderArtworkFallbackMode by AppSettings.folderArtworkFallbackMode.collectAsStateWithLifecycle()
    val artworkCacheKey = remember(imageUrl, folderArtworkFallbackMode) {
        "$imageUrl|folder-art-mode=${folderArtworkFallbackMode.persistedValue}"
    }
    // Seeded from the cache so a cover that has been seen before is on colour
    // in its first frame, with nothing to fade in from.
    var mesh by remember(artworkCacheKey) { mutableStateOf(artworkCacheKey.takeIf { imageUrl != null }?.let(meshCache::get)) }

    LaunchedEffect(imageUrl, artPx, folderArtworkFallbackMode) {
        if (imageUrl == null || mesh != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl.artworkAt(artPx))
            .size(MESH_PX)
            .allowHardware(false) // the sleeve has to be read back pixel by pixel
            .build()
        // Tried more than once, because this effect is keyed on the artwork and
        // nothing else: a read that fails is not retried by Coil and cannot be
        // re-triggered from here, so one dropped connection used to leave the
        // backdrop flat — no colour behind the player at all — until the track
        // changed. The cover on top of it has the same guard for the same
        // reason; see [NowPlayingScreen]'s artAttempt.
        repeat(MESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(MESH_RETRY_DELAY_MS)
            val result = SingletonImageLoader.get(context).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            if (bitmap != null) {
                val found = withContext(Dispatchers.Default) { meshOf(bitmap, imageUrl.hashCode()) }
                if (found != null) {
                    meshCache[artworkCacheKey] = found
                    mesh = found
                }
                // A cover that decoded but had no mesh in it — see [meshOf] —
                // is an answer, not a failure. Asking again gets the same one.
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(canvasFrame) {
        val frame = canvasFrame ?: return@LaunchedEffect
        // Same seed as the still read above, keyed off the URL rather than the
        // frame — a clip's frames are a moving target and aren't cached (the
        // next one for this URL is a different picture), but the *arrangement*
        // [shuffledBelowSeam] scrambles them into should hold still across a
        // refresh, or the layout would visibly reshuffle under its own colours
        // once a second.
        mesh = withContext(Dispatchers.Default) { meshOf(frame, imageUrl?.hashCode() ?: 0) } ?: mesh
    }
    return mesh
}

/**
 * The player's backdrop: [mesh] hung from where the artwork stops, and stretched
 * over everything below it.
 *
 * The mesh is anchored rather than centred. Its first row is the sleeve's own
 * bottom edge and it is drawn starting at [seam], so the colour immediately
 * under the artwork is the colour the artwork ended on and there is no join to
 * hide. Above [seam] that first row is simply held — that stretch is behind the
 * artwork, and holding it means the backdrop is continuous everywhere rather
 * than only below the sleeve.
 *
 * ### What this costs
 *
 * A full-screen [blur] is a `RenderEffect` over every pixel on screen, and it
 * is re-applied on every frame the backdrop is redrawn — so the bill is set by
 * how much of the time something is moving.
 *
 * A still sleeve is the cheap case and the usual one: the backdrop is drawn
 * once when the track changes and is then only composited, and the crossfade
 * costs its [MESH_FADE_MS] once per skip. A playing clip is the same case
 * repeated — one fade every few seconds, with the screen still in between; see
 * `MESH_REFRESH_MS` in `NowPlayingScreen` for the cadence.
 *
 * Each of those frames is made as cheap as it can be rather than merely rare.
 * The mesh is smoothed on the CPU before it is ever uploaded (see [MESH_TEX]),
 * so the blur is polish rather than the thing making the gradient smooth and
 * can stay narrow; and a fade frame redraws two stretched 32-texel quads and
 * nothing else — no decode, no readback, no sampling, all of which happen once
 * per read and off the main thread.
 *
 * The old backdrop paid a 60Hz blur permanently, for blobs orbiting behind a
 * screen nobody was looking at; see [MeshGradientBackground]'s note. This pays
 * it in bursts, and only while a clip is actually on screen and playing.
 */
@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    /**
     * How far down the surface the artwork's bottom edge sits. Zero for a
     * surface with no artwork over it, which puts the whole mesh on screen.
     */
    seam: Dp = 0.dp,
    /** What the surface is before any artwork has been read. */
    fallback: Color = FallbackBackdrop,
    /**
     * How far the mesh is smeared.
     *
     * Narrow on purpose, and not what makes the backdrop smooth: the grid is
     * already interpolated to [MESH_TEX] on the CPU with a curve that flattens
     * at every control point, so there are no creases left for this to remove.
     * It is here to take the last of the edge off, and a wider one would only
     * cost milliseconds — which matters, because a clip on screen asks for it
     * several times a second.
     */
    blurRadius: Dp = 32.dp,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val canBlur = !reduceDynamicBlur && blurRadius > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val configuration = LocalConfiguration.current
    // The artwork mesh is only 32x32. Blur it once at that resolution instead
    // of asking RenderEffect to process the whole 1080x2340 sheet every frame
    // while it moves. Ratios preserve the user-selected dp radius on screen.
    val renderMesh = remember(mesh, blurRadius, configuration.screenWidthDp, configuration.screenHeightDp, canBlur) {
        if (!canBlur || mesh == null) {
            mesh
        } else {
            val widthDp = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
            val heightDp = configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
            mesh.blurred(
                sigmaX = blurRadius.value * MESH_TEX / widthDp,
                sigmaY = blurRadius.value * MESH_TEX / heightDp,
            )
        }
    }

    // The mesh on screen, and the one fading in over it. Two states rather than
    // one animated value because what crossfades here is a pair of bitmaps, not
    // a pair of colours: the outgoing one has to stay drawable until the fade
    // has finished with it.
    var shown by remember { mutableStateOf(renderMesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(renderMesh) {
        val next = renderMesh ?: return@LaunchedEffect
        // A change arriving mid-fade. Whatever was fading in has been on screen
        // for a while and is what the new one should fade *from*; leaving it in
        // [incoming] would restart the fade from the mesh before it, and a clip
        // sending frames faster than the fade runs would never settle at all.
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect

        // Nothing to fade *from* on the first read, and nothing to fade at all
        // when the user has asked for less motion.
        if (current == null || reduceAnimation) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Outside the blur, so it is a flat opaque floor rather than
            // something the blur can thin out. The player is a sheet with the
            // app's own pages behind it and every pixel here has to be opaque:
            // an earlier pass blurred with `BlurredEdgeTreatment.Unbounded`,
            // whose decal tiling fades alpha inwards from each edge over the
            // whole blur radius, and the library showed through down both sides
            // and along the bottom. The default treatment clamps instead, which
            // cannot lose alpha — this is only here so nothing downstream can
            // reintroduce the bug.
            .background(fallback),
    ) {
        val seamY = seam.toPx().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, alpha = 1f) }
        // Read here rather than in composition: an Animatable read inside a
        // draw lambda invalidates the drawing and leaves composition out of it.
        incoming?.let { drawMesh(it, seamY, alpha = fade.value) }

        // Enough of a scrim to keep white text off a bright sleeve, and no
        // more. The old backdrop needed a heavier one because it lightened
        // every colour it drew to a fixed band; these are the sleeve's own,
        // and a sleeve that ends dark should leave a dark screen.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.30f),
                ),
            ),
        )
    }
}

/**
 * Paints [mesh] over the whole surface: the grid from [seamY] down, and its
 * first row held from there up. Two draws of one small texture, both stretched
 * by the sampler.
 */
private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val image = mesh.image
    val width = size.width.roundToInt()

    // Above the seam: the sleeve's bottom edge, held. Skipped when the seam is
    // at the very top, where there is no such stretch to fill.
    if (seamY > 0.5f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }

    // From the seam down: the mesh itself, stretched over whatever is left.
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** Drawn only until an artwork has been read — never a colour anyone chose. */
private val FallbackBackdrop = Color(0xFF121212)

/**
 * Meshes already read, keyed by artwork URL — the artwork at a URL cannot
 * change, so reading it twice buys a decode and a downsample for the same
 * answer. Touched from composition and from the resumption of
 * [rememberArtworkMesh]'s effect, both on the main thread, so it needs no lock.
 *
 * Clip frames never land here: the next one for the same URL is a different
 * picture, and there would be a new entry several times a second.
 */
private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtworkMesh>) = size > MESH_CACHE_ENTRIES
}

/** A session's worth of covers, at four kilobytes of texture each. */
private const val MESH_CACHE_ENTRIES = 64

/**
 * What a still sleeve is decoded at before it is averaged down. A whole
 * multiple of [MESH_GRID], so every cell is an equal number of source pixels
 * and no column comes out weighted differently from its neighbour.
 */
private const val MESH_PX = 120

/**
 * How many goes the backdrop's read gets before it is given up on, and how long
 * it waits between them.
 *
 * Matched to the cover's own retry in [NowPlayingScreen] rather than chosen
 * separately: the two read the same URL out of the same cache, so a run of
 * attempts that gave up at a different point from the cover's would be a way for
 * the backdrop and the artwork on top of it to disagree about whether this track
 * has a picture.
 */
private const val MESH_ATTEMPTS = 4

/** @see MESH_ATTEMPTS */
private const val MESH_RETRY_DELAY_MS = 1_500L

/**
 * How many cells across the mesh is.
 *
 * Coarse deliberately — see [ArtworkMesh]. Enough that a cover's layout
 * survives (where the bright part was, how much of the frame it took) and few
 * enough that nothing recognisable does.
 */
private const val MESH_GRID = 6

/**
 * How far the grid is interpolated on the CPU before the GPU stretches it.
 *
 * Bilinear filtering straight off a 6x6 texture is smooth but not *soft*: it is
 * piecewise-linear, and the eye finds the crease at every cell boundary when
 * they are a hundred and eighty pixels apart. Resampling to this first, with a
 * curve that flattens at each control point, leaves creases too small to see
 * before they are magnified — and it is what lets the GPU blur be narrow, which
 * is what makes following a clip affordable. It costs about a thousand lerps,
 * off the main thread, and the control points come through untouched, so the
 * artwork's proportions are exactly what they were.
 */
private const val MESH_TEX = 32

/**
 * How long the backdrop takes to change colour, whether that is a track skip or
 * the next frame of a clip playing over the sleeve.
 *
 * Long enough to read as the screen changing colour rather than cutting, and
 * comfortably inside the interval a clip's frames arrive on — see
 * `MESH_REFRESH_MS` in `NowPlayingScreen`. It has to finish before the next one
 * lands, or every fade is cut off partway and the backdrop never settles where
 * a frame actually put it.
 */
private const val MESH_FADE_MS = 900

/**
 * The most rows and columns of [source] that are actually looked at.
 *
 * A still sleeve is decoded at [MESH_PX] and falls well under this, but a clip
 * frame is whatever size the caller grabbed it at, and every pixel of it would
 * be read to produce thirty-six averages. Sampling on a stride instead bounds
 * the work at a fixed cost per frame however large the frame is, which is what
 * makes it safe to do this several times a second.
 */
private const val MESH_SAMPLE = 128

/**
 * Averages [source] into the mesh texture, flipped top to bottom and then
 * rotated sideways — see [rotatedBelowSeam] for why the flip alone isn't the
 * finish line.
 *
 * Row 0 of the grid is the sleeve's own bottom edge and later rows run back up
 * into it, which is the order [ArtworkMeshBackdrop] draws down the screen.
 *
 * A mean per cell, not a quantised swatch: what should sit under the artwork is
 * what a blur of the artwork would leave there, and a blur has no opinion about
 * which colour in the frame was the interesting one.
 */
private fun meshOf(source: Bitmap, seed: Int): ArtworkMesh? {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null

    // A cell nothing lands in would come out black, so the grid never asks for
    // more cells than the artwork has pixels.
    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)

    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    // One row at a time, so the scratch buffer is the width of the artwork
    // rather than the whole of it. A clip frame read whole is megabytes.
    val line = IntArray(width)
    var y = 0
    while (y < height) {
        source.getPixels(line, 0, width, 0, y, width, 1)
        // Flipped as it is read — see the note above.
        val rowBase = ((height - 1 - y) * rows / height) * cols
        var x = 0
        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
            x += colStep
        }
        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
    }
    val texels = grid.rotatedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
    val bitmap = Bitmap.createBitmap(texels, MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888)
    return ArtworkMesh(bitmap.asImageBitmap(), bitmap)
}

/** Small separable Gaussian used on the mesh before it reaches the GPU. */
private fun blurPixels(
    source: IntArray,
    width: Int,
    height: Int,
    sigma: Float,
    horizontal: Boolean,
): IntArray {
    if (sigma <= 0.05f) return source
    val radius = (sigma * 3f).roundToInt().coerceIn(1, MESH_TEX)
    val weights = FloatArray(radius * 2 + 1)
    var weightSum = 0f
    for (i in -radius..radius) {
        val weight = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
        weights[i + radius] = weight
        weightSum += weight
    }
    for (i in weights.indices) weights[i] /= weightSum

    val result = IntArray(source.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var alpha = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            for (offset in -radius..radius) {
                val sampleX = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val sampleY = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val pixel = source[sampleY * width + sampleX]
                val weight = weights[offset + radius]
                alpha += ((pixel ushr 24) and 0xFF) * weight
                red += ((pixel ushr 16) and 0xFF) * weight
                green += ((pixel ushr 8) and 0xFF) * weight
                blue += (pixel and 0xFF) * weight
            }
            result[y * width + x] =
                (alpha.roundToInt().coerceIn(0, 255) shl 24) or
                    (red.roundToInt().coerceIn(0, 255) shl 16) or
                    (green.roundToInt().coerceIn(0, 255) shl 8) or
                    blue.roundToInt().coerceIn(0, 255)
        }
    }
    return result
}

/**
 * Every row but row 0 — the seam row [meshOf] keeps literal, for the
 * no-visible-join reason [ArtworkMeshBackdrop] documents — rotated sideways
 * by the same random amount, and mirrored left-to-right half the time.
 *
 * The flip alone reads as a mirror on a cover with any real layout to it: a
 * face, a logo, a horizon line reappears upside down in the same columns it
 * left off in, and that symmetry is what makes the backdrop look like a
 * reflection rather than a colour field. An earlier version broke that by
 * shuffling every cell below the seam independently, which also broke the
 * *mesh*: a photo's neighbouring regions are usually close in colour, and a
 * random per-cell permutation puts wildly different cells next to each other
 * far more often than the photo itself ever did, so the smooth blend between
 * them read as a patchwork of small blended islands — a mosaic — rather than
 * the two or three broad blobs a real mesh gradient has.
 *
 * A cyclic shift doesn't have that problem: every cell keeps the exact
 * neighbours it started with, just carried around the row, so whatever
 * gradient existed between them survives untouched. What moves is *where on
 * screen* that gradient sits — which is exactly enough to stop the column
 * directly under the artwork's face or logo from being the same column that
 * face or logo was in above it, without inventing a single new boundary
 * between colours that weren't adjacent in the source.
 *
 * Seeded rather than re-rolled on every call: the same artwork should land in
 * the same arrangement each time it's read, or a canvas clip refreshing this
 * once a second (see `MESH_REFRESH_MS`) would show its layout visibly turning
 * underneath its own colours instead of just changing hue.
 */
private fun IntArray.rotatedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

/**
 * Smoothly resamples a [cols] x [rows] grid of control points up to a
 * [size] x [size] texture — see [MESH_TEX] for why this happens at all.
 */
private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

/** Flat at both ends, so no cell boundary shows up as a crease once magnified. */
private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/**
 * The one liberty taken with the artwork's colours.
 *
 * Saturation is nudged because averaging a block of pixels greys it — the mean
 * of a red stripe and the black around it is a dull maroon, and this puts back
 * roughly what the averaging took, not more. Lightness is only floored, off
 * pure black, which is not so much a colour choice as somewhere for the blur to
 * find an edge. Neither touches the *proportions*, which is the whole point of
 * this backdrop: a sleeve that is mostly black stays mostly black.
 */
private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f
