package com.example.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// Apple Music-inspired Tinted-Black Palette & Semantic Tokens
val SonoraBackground = Color(0xFF0A0607)
val SonoraBackgroundDeep = Color(0xFF0A0607)
val SonoraSurface = Color(0xFF150D0F)
val SonoraSurfaceElevated = Color(0xFF201517)
val SonoraGlassDark = Color(0x66140A0C)
val SonoraGlassLight = Color(0x1AFFFFFF)
val SonoraGlassBorderTop = Color(0x1AFFFFFF)
val SonoraGlassBorderBottom = Color(0x1AFFFFFF)

val SonoraCrimson = Color(0xFFFA2D48)
val SonoraCoral = Color(0xFFFF4D6D)
val SonoraRoseGlow = Color(0xFFFB5C74)
val SonoraPlum = Color(0xFF201517)
val SonoraViolet = Color(0xFF2B1C1F)

val SonoraTextPrimary = Color.White
val SonoraTextSecondary = Color(0x99FFFFFF) // 60% white
val SonoraTextTertiary = Color(0x73FFFFFF)

val SonoraSuccess = Color(0xFF34C759)
val SonoraSwitchActive = Color(0xFF34C759)
val SonoraWarning = Color(0xFFFF9F0A)

@Immutable
data class SonoraTintedPalette(
    val accent: Color,
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val pressed: Color,
    val glassTint: Color,
    val ambientTop: Color,
    val hairline: Color = Color.White.copy(alpha = 0.10f),
    val textPrimary: Color = Color.White,
    val textSecondary: Color = Color.White.copy(alpha = 0.60f),
    val isNearGray: Boolean = false
)

fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val l = (max + min) * 0.5f

    if (delta < 1e-5f) {
        return floatArrayOf(0f, 0f, l.coerceIn(0f, 1f))
    }

    val s = delta / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-5f)
    val rawHue = when (max) {
        r -> ((g - b) / delta) % 6f
        g -> ((b - r) / delta) + 2f
        else -> ((r - g) / delta) + 4f
    } * 60f
    val h = ((rawHue % 360f) + 360f) % 360f
    return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

fun hslToColor(
    hue: Float,
    saturation: Float,
    lightness: Float,
    alpha: Float = 1f
): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)

    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c * 0.5f

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

fun computeTintedBlackPalette(accent: Color): SonoraTintedPalette {
    val hsl = rgbToHsl(accent.red, accent.green, accent.blue)
    val hue = hsl[0]
    val srcSat = hsl[1]
    val nearGray = srcSat < 0.08f
    val satFactor = if (nearGray) 0f else 1f

    return SonoraTintedPalette(
        accent = accent,
        background = hslToColor(hue, 0.28f * satFactor, 0.035f),
        surface = hslToColor(hue, 0.24f * satFactor, 0.07f),
        elevated = hslToColor(hue, 0.22f * satFactor, 0.105f),
        pressed = hslToColor(hue, 0.20f * satFactor, 0.14f),
        glassTint = hslToColor(hue, 0.30f * satFactor, 0.06f, alpha = 0.40f),
        ambientTop = hslToColor(hue, 0.55f * satFactor, 0.22f, alpha = 0.55f),
        isNearGray = nearGray
    )
}

fun computeDominantTopColor(rawColor: Color): Color {
    val hsl = rgbToHsl(rawColor.red, rawColor.green, rawColor.blue)
    val hue = hsl[0]
    val s = if (hsl[1] < 0.08f) 0f else hsl[1].coerceAtMost(0.60f)
    val l = hsl[2].coerceIn(0.25f, 0.45f)
    return hslToColor(hue, s, l, alpha = 1f)
}

@Immutable
data class CoverGradientSpec(
    val topColor: Color,
    val midColor: Color,
    val bottomColor: Color,
    val artistTint: Color,
    val trackBarTint: Color,
    val activeStarTint: Color,
    val progressTint: Color = artistTint,
    val bottomIconTint: Color = artistTint
)

fun boostCoverSwatchColor(rawColor: Color): Color {
    val hsl = rgbToHsl(rawColor.red, rawColor.green, rawColor.blue)
    val s = if (hsl[1] >= 0.05f) hsl[1].coerceIn(0.55f, 0.90f) else 0.14f
    val l = hsl[2].coerceIn(0.28f, 0.42f)
    return hslToColor(hsl[0], s, l, alpha = 1f)
}

/**
 * Quintic smootherstep curve: 6t^5 - 15t^4 + 10t^3.
 */
fun smootherstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * Computes mask alpha at normalized vertical coordinate [fractionY] (0f = top, 1f = bottom)
 * so the bottom [fadeBottomFraction] (e.g. 0.38f) dissolves cleanly to 0f via `1 - smootherstep(t)`.
 */
fun canvasBottomFadeAlphaAt(fractionY: Float, fadeBottomFraction: Float = 0.38f): Float {
    val safeFade = fadeBottomFraction.coerceIn(0.05f, 0.85f)
    val fadeStart = 1f - safeFade
    if (fractionY <= fadeStart) return 1f
    val t = ((fractionY - fadeStart) / safeFade).coerceIn(0f, 1f)
    return (1f - smootherstep(t)).coerceIn(0f, 1f)
}

/**
 * Builds [stopCount] (default 16) color stops across the bottom [fadeBottomFraction] using `1 - smootherstep(t)`
 * for seamless DST_IN masking on both Compose `DrawScope` and Android `LinearGradient`.
 */
fun buildCanvasBottomFadeStops(
    fadeBottomFraction: Float = 0.38f,
    stopCount: Int = 16
): List<Pair<Float, Float>> {
    val safeStops = stopCount.coerceAtLeast(4)
    val fadeStart = 1f - fadeBottomFraction.coerceIn(0.05f, 0.85f)
    val out = ArrayList<Pair<Float, Float>>(safeStops + 1)
    out += 0f to 1f
    for (i in 0 until safeStops) {
        val t = i.toFloat() / (safeStops - 1).toFloat()
        val pos = fadeStart + (1f - fadeStart) * t
        val alpha = (1f - smootherstep(t)).coerceIn(0f, 1f)
        out += pos to alpha
    }
    return out
}

fun computeAverageRgbFromPixels(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0L
    for (px in pixels) {
        val alpha = (px ushr 24) and 0xFF
        if (alpha < 16) continue
        rSum += (px ushr 16) and 0xFF
        gSum += (px ushr 8) and 0xFF
        bSum += px and 0xFF
        count++
    }
    if (count == 0L) return null
    val r = (rSum / count).toInt().coerceIn(0, 255)
    val g = (gSum / count).toInt().coerceIn(0, 255)
    val b = (bSum / count).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

fun sampleBitmapBottomStripAverageRgb(
    bitmap: Bitmap,
    stripFraction: Float = 0.20f,
    targetGrid: Int = 32
): Int? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    val startRow = (h * (1f - stripFraction.coerceIn(0.05f, 1f))).toInt().coerceIn(0, h - 1)
    val stripHeight = (h - startRow).coerceAtLeast(1)
    val cols = targetGrid.coerceAtLeast(4)
    val rows = targetGrid.coerceAtLeast(4)
    val pixels = IntArray(cols * rows)
    var idx = 0
    for (ry in 0 until rows) {
        val py = (startRow + ((ry + 0.5f) / rows) * stripHeight).toInt().coerceIn(startRow, h - 1)
        for (rx in 0 until cols) {
            val px = (((rx + 0.5f) / cols) * w).toInt().coerceIn(0, w - 1)
            pixels[idx++] = bitmap.getPixel(px, py)
        }
    }
    return computeAverageRgbFromPixels(pixels)
}

/**
 * Derives Apple Music-style live background gradient (dark lightness 0.18..0.32, moderate saturation 0.35..0.70)
 * and matching light UI tint (lightness 0.75..0.85) from a bottom-20% average frame color.
 */
fun computeDynamicCanvasColors(
    avgBottomRgb: Int?,
    fallbackAccent: Color = SonoraCrimson,
    vibrantColorsRgb: List<Int> = emptyList()
): CoverGradientSpec {
    val rawEdge = avgBottomRgb?.let { Color(it).copy(alpha = 1f) }
        ?: vibrantColorsRgb.firstOrNull()?.let { Color(it).copy(alpha = 1f) }
        ?: fallbackAccent

    val rawHsl = rgbToHsl(rawEdge.red, rawEdge.green, rawEdge.blue)
    val isNeutralFrame = rawHsl[1] < 0.06f

    val topHue = rawHsl[0]
    val topSat = if (isNeutralFrame) {
        rawHsl[1].coerceIn(0f, 0.10f)
    } else {
        rawHsl[1].coerceIn(0.35f, 0.70f)
    }
    val topLight = rawHsl[2].coerceIn(0.18f, 0.32f)
    val topColor = hslToColor(topHue, topSat, topLight, alpha = 1f)

    val secondaryRaw = vibrantColorsRgb.getOrNull(1)?.let { Color(it).copy(alpha = 1f) }
    val secHsl = secondaryRaw?.let { rgbToHsl(it.red, it.green, it.blue) }

    val midHue = if (secHsl != null && !isNeutralFrame && secHsl[1] >= 0.10f) {
        (topHue * 0.75f + secHsl[0] * 0.25f)
    } else topHue
    val midSat = if (isNeutralFrame) topSat else (topSat * 0.92f).coerceIn(0.32f, 0.68f)
    val midLight = (topLight * 0.68f).coerceIn(0.12f, 0.22f)
    val midColor = hslToColor(midHue, midSat, midLight, alpha = 1f)

    val botSat = if (isNeutralFrame) topSat else (topSat * 0.84f).coerceIn(0.28f, 0.64f)
    val botLight = (topLight * 0.42f).coerceIn(0.08f, 0.14f)
    val bottomColor = hslToColor(topHue, botSat, botLight, alpha = 1f)

    // Light tint in [0.75..0.85] lightness for progress bar, volume, favorite star, artist line, bottom icons
    val lightSat = if (isNeutralFrame) 0.04f else (topSat * 0.85f).coerceIn(0.35f, 0.68f)
    val lightTint = hslToColor(topHue, lightSat, 0.80f, alpha = 1f)
    val starTint = hslToColor(topHue, if (isNeutralFrame) 0.08f else 0.78f, 0.82f, alpha = 1f)
    val trackBgTint = hslToColor(topHue, lightSat * 0.75f, 0.78f, alpha = 0.28f)

    return CoverGradientSpec(
        topColor = topColor,
        midColor = midColor,
        bottomColor = bottomColor,
        artistTint = lightTint,
        trackBarTint = trackBgTint,
        activeStarTint = starTint,
        progressTint = lightTint,
        bottomIconTint = lightTint
    )
}

fun computeCoverGradientSpec(
    vibrantColorsRgb: List<Int>,
    bottomEdgeRgb: Int? = null,
    fallbackAccent: Color = SonoraCrimson
): CoverGradientSpec {
    val boostedSwatches = vibrantColorsRgb
        .asSequence()
        .map { Color(it).copy(alpha = 1f) }
        .filter { rgbToHsl(it.red, it.green, it.blue)[1] >= 0.08f }
        .take(3)
        .map { boostCoverSwatchColor(it) }
        .toList()

    val primary = boostedSwatches.firstOrNull() ?: boostCoverSwatchColor(fallbackAccent)
    val secondary = boostedSwatches.getOrNull(1) ?: primary
    val tertiary = boostedSwatches.getOrNull(2) ?: secondary

    val primaryHsl = rgbToHsl(primary.red, primary.green, primary.blue)

    val topColor = if (bottomEdgeRgb != null) {
        val edgeColor = Color(bottomEdgeRgb).copy(alpha = 1f)
        val edgeHsl = rgbToHsl(edgeColor.red, edgeColor.green, edgeColor.blue)
        if (edgeHsl[1] >= 0.08f) {
            val edgeSat = edgeHsl[1].coerceIn(0.35f, 0.70f)
            val edgeLight = edgeHsl[2].coerceIn(0.18f, 0.32f)
            hslToColor(edgeHsl[0], edgeSat, edgeLight, alpha = 1f)
        } else {
            hslToColor(primaryHsl[0], primaryHsl[1].coerceIn(0.35f, 0.70f), 0.26f, alpha = 1f)
        }
    } else {
        hslToColor(primaryHsl[0], primaryHsl[1].coerceIn(0.35f, 0.70f), primaryHsl[2].coerceIn(0.18f, 0.32f), alpha = 1f)
    }

    val topHsl = rgbToHsl(topColor.red, topColor.green, topColor.blue)
    val midHsl = rgbToHsl(secondary.red, secondary.green, secondary.blue)
    val botHsl = rgbToHsl(tertiary.red, tertiary.green, tertiary.blue)

    // Step downward through darker tones of the extracted cover color, never pure black
    val midColor = hslToColor(
        hue = (topHsl[0] * 0.55f + midHsl[0] * 0.45f),
        saturation = maxOf(topHsl[1], midHsl[1]).coerceIn(0.35f, 0.70f),
        lightness = (topHsl[2] * 0.65f).coerceIn(0.13f, 0.22f),
        alpha = 1f
    )
    val bottomColor = hslToColor(
        hue = (topHsl[0] * 0.65f + botHsl[0] * 0.35f),
        saturation = maxOf(topHsl[1] * 0.85f, 0.35f).coerceIn(0.35f, 0.70f),
        lightness = (topHsl[2] * 0.42f).coerceIn(0.09f, 0.15f),
        alpha = 1f
    )

    val tintHue = topHsl[0]
    val lightTint = hslToColor(tintHue, 0.52f, 0.80f, alpha = 1f)
    val trackBarTint = hslToColor(tintHue, 0.36f, 0.78f, alpha = 0.28f)
    val activeStarTint = hslToColor(tintHue, 0.78f, 0.82f, alpha = 1f)

    return CoverGradientSpec(
        topColor = topColor,
        midColor = midColor,
        bottomColor = bottomColor,
        artistTint = lightTint,
        trackBarTint = trackBarTint,
        activeStarTint = activeStarTint,
        progressTint = lightTint,
        bottomIconTint = lightTint
    )
}
