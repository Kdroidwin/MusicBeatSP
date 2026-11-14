package com.music.bitchord.ui.theme

import android.app.Activity
import android.os.Build
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.rememberArtworkColors

// Apple Music's signature red. No longer the primary accent, but kept for the
// spots (Replay's rank badge) that want that specific red regardless of theme.
val AccentRed = Color(0xFFFA2D48)

/** True when the animated player artwork is also the app's page background. */
val LocalPlayerBackdropThemeEnabled = compositionLocalOf { false }

/** Page surfaces should let the player backdrop show through in its theme. */
@Composable
fun pageBackgroundColor(): Color = if (LocalPlayerBackdropThemeEnabled.current) {
    Color.Transparent
} else {
    MaterialTheme.colorScheme.background
}

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
)

private val LightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F7F9),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
)

private val AmoledColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF080808),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF242424),
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
)

/**
 * SF Pro Display, the face Apple Music itself is set in. Only the weights the
 * type scale actually asks for are bundled; Compose synthesises nothing, so a
 * missing weight would silently fall back to the nearest one shipped.
 */
val SFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

// Heavy, tight typography — the backbone of the Apple Music look.
private val BitChordTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
).withFamily(SFProDisplay)

/** Applies [family] to every style in the scale, so nothing is left on Roboto. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

@Composable
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledBlack: Boolean = false,
    artworkUrl: String? = null,
    matchArtwork: Boolean = false,
    playerScreenColors: Boolean = false,
    playerBackgroundTheme: Boolean = false,
    matchButtonAccentToArtwork: Boolean = false,
    content: @Composable () -> Unit,
) {
    val customFontPath by AppSettings.customFontPath.collectAsStateWithLifecycle()
    val typography = remember(customFontPath) {
        customFontPath.takeIf { it.isNotEmpty() }
            ?.let(CustomFont::familyFor)
            ?.let { BitChordTypography.withFamily(it) }
            ?: BitChordTypography
    }
    MaterialTheme(
        colorScheme = when {
            amoledBlack -> AmoledColors
            darkTheme -> DarkColors
            else -> LightColors
        },
        typography = typography,
    ) {
        // Resolve the playing sleeve through the existing palette/cache path.
        val base = MaterialTheme.colorScheme
        val artwork = rememberArtworkPalette(artworkUrl, dark = darkTheme)
        // The player screen paints a four-colour mesh instead of Album Art
        // theme's single dominant-colour surface. Reuse that mesh palette as
        // its own app theme, and keep the stock dark scheme until the artwork
        // is actually decoded so this mode never flashes unrelated fallback
        // colours on the first frame.
        val playerMesh = if (playerScreenColors && artworkUrl != null) {
            rememberArtworkColors(artworkUrl)
        } else null
        val colors = if (matchArtwork && artworkUrl != null) {
            // Artwork mode is a full app theme. Tinting only the accents made
            // it look like a minor player toggle while the library, sheets,
            // and navigation stayed monochrome. Carry the sleeve palette into
            // Material surfaces too, while retaining the existing contrast
            // rules from ArtworkPalette for body text and secondary labels.
            base.copy(
                primary = artwork.accent,
                onPrimary = if (darkTheme) Color.Black else Color.White,
                primaryContainer = artwork.elevated,
                onPrimaryContainer = artwork.onBackground,
                secondary = artwork.accent,
                onSecondary = if (darkTheme) Color.Black else Color.White,
                secondaryContainer = artwork.elevated,
                onSecondaryContainer = artwork.onBackground,
                tertiary = artwork.accent,
                onTertiary = if (darkTheme) Color.Black else Color.White,
                tertiaryContainer = artwork.wash,
                onTertiaryContainer = artwork.onBackground,
                background = artwork.background,
                onBackground = artwork.onBackground,
                surface = artwork.elevated,
                onSurface = artwork.onBackground,
                surfaceVariant = artwork.wash,
                onSurfaceVariant = artwork.onBackgroundVariant,
                surfaceTint = artwork.accent,
                outline = artwork.divider,
                outlineVariant = artwork.divider,
                surfaceDim = if (darkTheme) artwork.background else artwork.elevated,
                surfaceBright = if (darkTheme) artwork.wash else artwork.background,
                surfaceContainerLowest = artwork.background,
                surfaceContainerLow = lerp(artwork.background, artwork.elevated, 0.22f),
                surfaceContainer = lerp(artwork.background, artwork.elevated, 0.38f),
                surfaceContainerHigh = lerp(artwork.background, artwork.elevated, 0.62f),
                surfaceContainerHighest = artwork.elevated,
            )
        } else if (playerScreenColors && playerMesh?.isResolved == true) {
            playerScreenColorScheme(base, playerMesh.colors)
        } else if (matchButtonAccentToArtwork && artworkUrl != null) {
            // A separate, smaller option for people who want artwork-coloured
            // buttons and selection indicators while keeping the chosen theme's
            // backgrounds, cards, and text colours intact.
            val accent = artwork.accent
            val onAccent = if (accent.luminance() > 0.52f) Color.Black else Color.White
            val accentContainer = lerp(base.surface, accent, 0.24f)
            base.copy(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = accentContainer,
                onPrimaryContainer = base.onSurface,
                secondary = accent,
                onSecondary = onAccent,
                secondaryContainer = accentContainer,
                onSecondaryContainer = base.onSurface,
                tertiary = accent,
                onTertiary = onAccent,
                tertiaryContainer = accentContainer,
                onTertiaryContainer = base.onSurface,
                surfaceTint = accent,
            )
        } else base
        CompositionLocalProvider(LocalPlayerBackdropThemeEnabled provides playerBackgroundTheme) {
            MaterialTheme(colorScheme = colors, typography = typography, content = content)
        }
    }
}

/**
 * The same artwork-derived Material colors used by Player Colors / Player background,
 * available to optional surfaces without changing the app's selected theme.
 */
@Composable
fun rememberPlayerBackdropColorScheme(artworkUrl: String?): ColorScheme {
    val mesh = rememberArtworkColors(artworkUrl)
    val base = MaterialTheme.colorScheme
    return remember(base, mesh) {
        playerScreenColorScheme(base, mesh.colors)
    }
}

/** Material surfaces tinted from the four swatches used by the player backdrop. */
private fun playerScreenColorScheme(base: ColorScheme, swatches: List<Color>): ColorScheme {
    val colors = (swatches + DarkColors.primary).take(4)
    val background = colors[0].playerTone(0.10f)
    val surface = colors[1].playerTone(0.155f)
    val surfaceVariant = colors[2].playerTone(0.205f)
    val elevated = colors[3].playerTone(0.255f)
    val primary = colors[0].playerTone(0.70f)
    val secondary = colors[1].playerTone(0.67f)
    val tertiary = colors[2].playerTone(0.65f)
    val outline = colors[3].playerTone(0.40f)
    return base.copy(
        primary = primary,
        onPrimary = if (primary.luminance() > 0.40f) Color.Black else Color.White,
        primaryContainer = surfaceVariant,
        onPrimaryContainer = Color.White,
        secondary = secondary,
        onSecondary = if (secondary.luminance() > 0.40f) Color.Black else Color.White,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = Color.White,
        tertiary = tertiary,
        onTertiary = if (tertiary.luminance() > 0.40f) Color.Black else Color.White,
        tertiaryContainer = elevated,
        onTertiaryContainer = Color.White,
        background = background,
        onBackground = Color.White,
        surface = surface,
        onSurface = Color.White,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = Color.White.copy(alpha = 0.78f),
        surfaceTint = primary,
        outline = outline,
        outlineVariant = outline.copy(alpha = 0.72f),
        surfaceDim = background,
        surfaceBright = elevated,
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(background, surface, 0.30f),
        surfaceContainer = lerp(background, surfaceVariant, 0.42f),
        surfaceContainerHigh = lerp(surface, elevated, 0.55f),
        surfaceContainerHighest = elevated,
    )
}

private fun Color.playerTone(lightness: Float): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    hsl[1] = (hsl[1] * 1.18f).coerceAtMost(0.82f)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the theme the app is actually painting — with one exception,
 * the player, which is dark artwork regardless and so always wants light
 * glyphs. Hence a parameter rather than reading the theme here.
 */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val forceWhiteNavigationBar by AppSettings.forceWhiteNavigationBar.collectAsStateWithLifecycle()
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Let the edge-to-edge app backdrop show through the gesture area;
            // the system contrast scrim would make artwork pages black at the bottom.
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = dark
            // false asks Android for light navigation glyphs / gesture handle.
            isAppearanceLightNavigationBars = if (forceWhiteNavigationBar) false else dark
        }
    }
}

/** Applies visibility preferences to a modal surface's own window as well as its host. */
@Composable
fun SystemBarVisibility(
    showStatusBar: Boolean,
    showNavigationBar: Boolean,
    revealStatusBarInstantly: Boolean = false,
) {
    val forceWhiteNavigationBar by AppSettings.forceWhiteNavigationBar.collectAsStateWithLifecycle()
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    var instantRevealApplied by remember(window) { mutableStateOf(false) }
    SideEffect {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showStatusBar) {
                if (revealStatusBarInstantly) {
                    if (!instantRevealApplied) showStatusBarImmediately(window, this)
                    instantRevealApplied = true
                } else {
                    instantRevealApplied = false
                    show(WindowInsetsCompat.Type.statusBars())
                }
            } else {
                instantRevealApplied = false
                hide(WindowInsetsCompat.Type.statusBars())
            }
            if (showNavigationBar) show(WindowInsetsCompat.Type.navigationBars())
            else hide(WindowInsetsCompat.Type.navigationBars())
            // A modal sheet can restore automatic contrast when it changes bar
            // visibility. Apply the explicit white-glyph preference last.
            if (forceWhiteNavigationBar) isAppearanceLightNavigationBars = false
        }
    }
}

/** Snaps the owning modal window's status bar to its shown state during player close. */
private fun showStatusBarImmediately(window: Window, fallback: WindowInsetsControllerCompat) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        fallback.show(WindowInsetsCompat.Type.statusBars())
        return
    }
    val controller = window.insetsController
    if (controller == null) {
        fallback.show(WindowInsetsCompat.Type.statusBars())
        return
    }
    runCatching {
        controller.controlWindowInsetsAnimation(
            WindowInsets.Type.statusBars(),
            0L,
            null,
            null,
            object : WindowInsetsAnimationControlListener {
                override fun onReady(animationController: WindowInsetsAnimationController, types: Int) {
                    if (types and WindowInsets.Type.statusBars() != 0) {
                        animationController.finish(true)
                    } else {
                        fallback.show(WindowInsetsCompat.Type.statusBars())
                    }
                }

                override fun onFinished(animationController: WindowInsetsAnimationController) = Unit

                override fun onCancelled(animationController: WindowInsetsAnimationController?) {
                    fallback.show(WindowInsetsCompat.Type.statusBars())
                }
            },
        )
    }.onFailure { fallback.show(WindowInsetsCompat.Type.statusBars()) }
}

// Walks up the Compose view hierarchy to find a DialogWindowProvider (e.g. modal player) before falling back to Activity context.
private fun findWindow(view: android.view.View): android.view.Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is androidx.compose.ui.window.DialogWindowProvider) {
            return parent.window
        }
        parent = parent.parent
    }
    var context = view.context
    while (context is android.content.ContextWrapper) {
        if (context is Activity) {
            return context.window
        }
        context = context.baseContext
    }
    return null
}
