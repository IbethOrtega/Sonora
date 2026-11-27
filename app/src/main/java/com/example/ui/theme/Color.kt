package com.example.ui.theme

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
    val activeStarTint: Color
)

fun boostCoverSwatchColor(rawColor: Color): Color {
    val hsl = rgbToHsl(rawColor.red, rawColor.green, rawColor.blue)
    val s = if (hsl[1] >= 0.05f) hsl[1].coerceIn(0.55f, 0.90f) else 0.14f
    val l = hsl[2].coerceIn(0.28f, 0.42f)
    return hslToColor(hsl[0], s, l, alpha = 1f)
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
            val edgeSat = edgeHsl[1].coerceIn(0.55f, 0.90f)
            val edgeLight = edgeHsl[2].coerceIn(0.28f, 0.42f)
            hslToColor(edgeHsl[0], edgeSat, edgeLight, alpha = 1f)
        } else {
            hslToColor(primaryHsl[0], primaryHsl[1].coerceAtLeast(0.55f), 0.30f, alpha = 1f)
        }
    } else {
        primary
    }

    val topHsl = rgbToHsl(topColor.red, topColor.green, topColor.blue)
    val midHsl = rgbToHsl(secondary.red, secondary.green, secondary.blue)
    val botHsl = rgbToHsl(tertiary.red, tertiary.green, tertiary.blue)

    // Step downward through darker tones of the extracted cover color, never pure black
    val midColor = hslToColor(
        hue = (topHsl[0] * 0.55f + midHsl[0] * 0.45f),
        saturation = maxOf(topHsl[1], midHsl[1]).coerceIn(0.52f, 0.86f),
        lightness = (topHsl[2] * 0.62f).coerceIn(0.16f, 0.27f),
        alpha = 1f
    )
    val bottomColor = hslToColor(
        hue = (topHsl[0] * 0.65f + botHsl[0] * 0.35f),
        saturation = maxOf(topHsl[1] * 0.85f, 0.46f).coerceIn(0.46f, 0.82f),
        lightness = (topHsl[2] * 0.35f).coerceIn(0.095f, 0.16f),
        alpha = 1f
    )

    val tintHue = topHsl[0]
    val artistTint = hslToColor(tintHue, 0.48f, 0.83f, alpha = 1f)
    val trackBarTint = hslToColor(tintHue, 0.34f, 0.74f, alpha = 0.26f)
    val activeStarTint = hslToColor(primaryHsl[0], 0.82f, 0.72f, alpha = 1f)

    return CoverGradientSpec(
        topColor = topColor,
        midColor = midColor,
        bottomColor = bottomColor,
        artistTint = artistTint,
        trackBarTint = trackBarTint,
        activeStarTint = activeStarTint
    )
}

