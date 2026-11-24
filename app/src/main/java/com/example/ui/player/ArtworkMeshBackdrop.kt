package com.example.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.model.PlayerBackdropStyle
import com.example.ui.components.upgradeArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class BackdropContrastMetrics(
    val useDarkStatusBarIcons: Boolean = false,
    val topBandScrimAlpha: Float = 0.24f
)

private data class SampledArtworkGrid(
    val artworkUrl: String,
    val gridColors: List<Color>,
    val reflectedColors: List<Color>,
    val topLuminance: Float
)

fun Modifier.artworkBottomFade(): Modifier = composed {
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Black,
                    0.74f to Color.Black,
                    0.92f to Color.Black.copy(alpha = 0.44f),
                    1.0f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

@Composable
fun ArtworkMeshBackdrop(
    artworkUrl: String,
    style: PlayerBackdropStyle,
    accent: Color,
    ambientPrimary: Color,
    ambientSecondary: Color,
    isPlaying: Boolean,
    isReducedMotion: Boolean,
    onContrastChange: (BackdropContrastMetrics) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val highResUrl = remember(artworkUrl) { upgradeArtworkUrl(artworkUrl) }

    val fallbackGrid = remember(ambientPrimary, ambientSecondary, accent) {
        SampledArtworkGrid(
            artworkUrl = "",
            gridColors = listOf(
                ambientPrimary.copy(alpha = 0.72f),
                ambientSecondary.copy(alpha = 0.76f),
                accent.copy(alpha = 0.48f),
                Color(0xFF10111A),
                ambientSecondary.copy(alpha = 0.68f),
                Color(0xFF0A0B12),
                ambientPrimary.copy(alpha = 0.56f),
                Color(0xFF08080D),
                Color(0xFF07070B)
            ),
            reflectedColors = listOf(
                ambientSecondary.copy(alpha = 0.45f),
                ambientPrimary.copy(alpha = 0.40f),
                Color(0xFF050508)
            ),
            topLuminance = 0.14f
        )
    }

    var sampledGrid by remember { mutableStateOf(fallbackGrid) }

    LaunchedEffect(highResUrl, context, ambientPrimary, ambientSecondary) {
        if (highResUrl.isBlank()) {
            sampledGrid = fallbackGrid
            onContrastChange(BackdropContrastMetrics(useDarkStatusBarIcons = false, topBandScrimAlpha = 0.24f))
            return@LaunchedEffect
        }
        val extracted = sampleArtworkBitmapGrid(context, highResUrl)
        if (extracted != null) {
            sampledGrid = extracted
            val brightTop = extracted.topLuminance > 0.62f
            onContrastChange(
                BackdropContrastMetrics(
                    useDarkStatusBarIcons = brightTop,
                    topBandScrimAlpha = if (brightTop) 0.44f else 0.22f
                )
            )
        } else {
            sampledGrid = fallbackGrid
            onContrastChange(BackdropContrastMetrics(useDarkStatusBarIcons = false, topBandScrimAlpha = 0.24f))
        }
    }

    val motionEnabled = isPlaying && !isReducedMotion
    val phaseAnim = rememberInfiniteTransition(label = "mesh_backdrop_drift")
    val driftPhase by phaseAnim.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift_phase"
    )
    val effectivePhase = if (motionEnabled) driftPhase else 0.4f

    Box(modifier = modifier.background(Color(0xFF07070B))) {
        when (style) {
            PlayerBackdropStyle.SIMPLE -> {
                if (highResUrl.isNotBlank()) {
                    val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(85.dp) else Modifier
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(highResUrl)
                            .crossfade(360)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(blurMod)
                            .alpha(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.52f else 0.22f)
                    )
                }
            }

            PlayerBackdropStyle.LEGACY -> {
                Crossfade(
                    targetState = sampledGrid,
                    animationSpec = tween(durationMillis = 650),
                    label = "legacy_orb_crossfade",
                    modifier = Modifier.fillMaxSize()
                ) { grid ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val c0 = grid.gridColors.getOrElse(0) { ambientPrimary }
                        val c1 = grid.gridColors.getOrElse(4) { ambientSecondary }
                        val c2 = grid.gridColors.getOrElse(6) { accent }
                        val c3 = grid.reflectedColors.getOrElse(0) { ambientSecondary }

                        val orbs = listOf(
                            Triple(c0, Offset(size.width * (0.25f + 0.14f * cos(effectivePhase)), size.height * (0.22f + 0.10f * sin(effectivePhase))), size.maxDimension * 0.62f),
                            Triple(c1, Offset(size.width * (0.78f - 0.12f * sin(effectivePhase)), size.height * (0.34f + 0.12f * cos(effectivePhase * 0.8f))), size.maxDimension * 0.58f),
                            Triple(c2, Offset(size.width * (0.32f + 0.15f * sin(effectivePhase * 0.7f)), size.height * (0.68f - 0.08f * cos(effectivePhase))), size.maxDimension * 0.54f),
                            Triple(c3, Offset(size.width * (0.75f + 0.10f * cos(effectivePhase * 0.6f)), size.height * (0.80f - 0.06f * sin(effectivePhase))), size.maxDimension * 0.56f)
                        )
                        orbs.forEach { (col, center, radius) ->
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(col.copy(alpha = 0.58f), col.copy(alpha = 0.14f), Color.Transparent),
                                    center = center,
                                    radius = radius
                                ),
                                center = center,
                                radius = radius
                            )
                        }
                    }
                }
            }

            PlayerBackdropStyle.DEFAULT -> {
                Crossfade(
                    targetState = sampledGrid,
                    animationSpec = tween(durationMillis = 700),
                    label = "mesh_grid_crossfade",
                    modifier = Modifier.fillMaxSize()
                ) { grid ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val colors = grid.gridColors
                        val reflected = grid.reflectedColors

                        // Base deep vertical atmospheric wash + inverted bottom artwork reflection
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to colors.getOrElse(0) { ambientPrimary }.copy(alpha = 0.62f),
                                0.42f to colors.getOrElse(4) { ambientSecondary }.copy(alpha = 0.54f),
                                0.74f to reflected.getOrElse(0) { ambientPrimary }.copy(alpha = 0.42f),
                                1.0f to Color(0xFF050508)
                            )
                        )

                        // Smooth multi-node bilinear-feeling radial mesh lobes
                        val meshNodes = listOf(
                            Offset(0.18f + 0.10f * sin(effectivePhase), 0.16f + 0.06f * cos(effectivePhase)),
                            Offset(0.84f - 0.10f * cos(effectivePhase * 0.9f), 0.22f + 0.08f * sin(effectivePhase * 0.8f)),
                            Offset(0.50f + 0.12f * cos(effectivePhase * 0.6f), 0.48f + 0.07f * sin(effectivePhase)),
                            Offset(0.20f + 0.08f * cos(effectivePhase * 0.7f), 0.72f - 0.05f * sin(effectivePhase)),
                            Offset(0.82f - 0.08f * sin(effectivePhase * 0.8f), 0.76f + 0.05f * cos(effectivePhase * 0.7f))
                        )
                        val nodeColors = listOf(
                            colors.getOrElse(0) { ambientPrimary },
                            colors.getOrElse(2) { ambientSecondary },
                            colors.getOrElse(4) { accent },
                            reflected.getOrElse(0) { ambientSecondary },
                            reflected.getOrElse(1) { ambientPrimary }
                        )
                        val radius = size.maxDimension * 0.74f
                        meshNodes.forEachIndexed { idx, normPos ->
                            val center = Offset(size.width * normPos.x, size.height * normPos.y)
                            val c = nodeColors[idx]
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        c.copy(alpha = 0.56f),
                                        c.copy(alpha = 0.20f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = radius
                                ),
                                center = center,
                                radius = radius
                            )
                        }
                    }
                }
            }
        }

        // Legibility gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.34f),
                        0.45f to Color(0xFF07070B).copy(alpha = 0.42f),
                        0.80f to Color(0xFF050508).copy(alpha = 0.76f),
                        1.0f to Color.Black.copy(alpha = 0.92f)
                    )
                )
        )
    }
}

private suspend fun sampleArtworkBitmapGrid(
    context: android.content.Context,
    artworkUrl: String
): SampledArtworkGrid? = withContext(Dispatchers.IO) {
    val loader = ImageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(artworkUrl)
        .size(48, 48)
        .allowHardware(false)
        .build()

    val result = runCatching { loader.execute(request) }.getOrNull() as? SuccessResult ?: return@withContext null
    val rawBitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
    val bmp = if (rawBitmap.config == Bitmap.Config.ARGB_8888) rawBitmap else rawBitmap.copy(Bitmap.Config.ARGB_8888, false)

    val w = bmp.width.coerceAtLeast(1)
    val h = bmp.height.coerceAtLeast(1)

    // Measure top band luminance for adaptive status bar / handle contrast
    var lumSum = 0.0
    var lumSamples = 0
    val topRows = (h / 4).coerceAtLeast(1)
    for (y in 0 until topRows) {
        for (x in 0 until w step 2) {
            val px = bmp.getPixel(x, y)
            val r = ((px shr 16) and 0xFF) / 255.0
            val g = ((px shr 8) and 0xFF) / 255.0
            val b = (px and 0xFF) / 255.0
            lumSum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            lumSamples++
        }
    }
    val topLuminance = if (lumSamples > 0) (lumSum / lumSamples).toFloat() else 0.15f

    // Sample 3x3 mesh grid + inverted bottom reflection
    val grid = ArrayList<Color>(9)
    val xs = listOf(0.18f, 0.50f, 0.82f)
    val ys = listOf(0.18f, 0.50f, 0.82f)
    for (yf in ys) {
        for (xf in xs) {
            val sx = (xf * (w - 1)).toInt().coerceIn(0, w - 1)
            val sy = (yf * (h - 1)).toInt().coerceIn(0, h - 1)
            val px = bmp.getPixel(sx, sy)
            grid.add(Color(px).copy(alpha = 1f))
        }
    }
    val reflected = listOf(
        grid.getOrElse(6) { Color(0xFF12121A) },
        grid.getOrElse(7) { Color(0xFF12121A) },
        grid.getOrElse(8) { Color(0xFF12121A) }
    )
    SampledArtworkGrid(
        artworkUrl = artworkUrl,
        gridColors = grid,
        reflectedColors = reflected,
        topLuminance = topLuminance
    )
}
