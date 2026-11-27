package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.GlassStyle
import com.example.ui.theme.LocalSonoraGlassStyle
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraTextPrimary
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/**
 * Provides the captured LayerBackdrop from the main screen content behind floating navigation,
 * mini player, top floating controls, and bottom sheets.
 */
val LocalSonoraBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }

private const val LIQUID_GLASS_RESOLUTION_SCALE = 0.33f
private val LIQUID_GLASS_BLUR_DP = 8.dp
private val LIQUID_GLASS_MAX_REFRACTION_DP = 48.dp
private val LIQUID_GLASS_HAIRLINE_WIDTH = 0.5.dp

@Composable
fun rememberSonoraLayerBackdrop(): LayerBackdrop {
    val bgColor = LocalSonoraPalette.current.background
    return rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
}

@Composable
fun ProvideSonoraBackdrop(
    backdrop: LayerBackdrop?,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalSonoraBackdrop provides backdrop,
        LocalLiquidGlassEnabled provides enabled,
        content = content
    )
}

fun Modifier.sonoraBackdropSource(
    backdrop: LayerBackdrop?,
    enabled: Boolean
): Modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled && backdrop != null) {
    this.layerBackdrop(backdrop)
} else {
    this
}

/**
 * Apple Music-inspired Liquid Glass surface via Kyant0/backdrop on API 31+:
 * - 8 dp blur, vibrancy = 1, edge lens refraction (0.5 height, 0.5 intensity, max 48 dp)
 * - 0.33 downsampled backdrop pass
 * - Neutral white 0.5 dp 10% hairline + directional specular reflection
 * - Glass tint computed from accent Hue (S 0.30, L 0.06 at 40% alpha)
 * - Pre-Android 12 fallback: tint + 0.5 dp neutral hairline + directional reflection only.
 */
fun Modifier.sonoraLiquidGlassSurface(
    shape: Shape,
    blurRadius: Dp = LIQUID_GLASS_BLUR_DP,
    refractionHeight: Dp = 24.dp,
    refractionAmount: Dp = 12.dp,
    surfaceAlpha: Float = 0.40f,
    fallbackAlphaTop: Float = 0.40f,
    fallbackAlphaBottom: Float = 0.40f,
    borderWidth: Dp = LIQUID_GLASS_HAIRLINE_WIDTH
): Modifier = composed {
    val backdrop = LocalSonoraBackdrop.current
    val liquidEnabled = LocalLiquidGlassEnabled.current
    val isObsidian = LocalSonoraGlassStyle.current == GlassStyle.OBSIDIAN
    val palette = LocalSonoraPalette.current

    val resolvedTint = palette.glassTint.copy(
        alpha = if (surfaceAlpha > 0.50f) surfaceAlpha.coerceIn(0.40f, 0.72f) else 0.40f
    )
    val directionalSheen = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.14f),
            Color.White.copy(alpha = 0.02f),
            Color.White.copy(alpha = 0.08f)
        )
    )
    val hairlineBorder = BorderStroke(borderWidth, Color.White.copy(alpha = 0.10f))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && liquidEnabled && !isObsidian && backdrop != null) {
        val effectiveBlur = minOf(blurRadius, LIQUID_GLASS_BLUR_DP)
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(effectiveBlur.toPx())
                if (shape is CornerBasedShape && size.minDimension > 1f) {
                    val maxRefractionPx = LIQUID_GLASS_MAX_REFRACTION_DP.toPx()
                    val heightPx = (size.minDimension * 0.5f)
                        .coerceAtMost(maxRefractionPx)
                        .coerceAtLeast(1f)
                    val amountPx = (heightPx * 0.5f)
                        .coerceAtMost(maxRefractionPx)
                        .coerceAtLeast(0.5f)
                    lens(
                        refractionHeight = heightPx,
                        refractionAmount = amountPx,
                        depthEffect = false
                    )
                }
            },
            highlight = {
                Highlight(
                    width = LIQUID_GLASS_HAIRLINE_WIDTH,
                    alpha = 0.24f,
                    style = HighlightStyle.Default()
                )
            },
            shadow = {
                Shadow(
                    radius = 12.dp,
                    color = Color.Black.copy(alpha = 0.26f)
                )
            },
            onDrawBackdrop = { drawBackdrop ->
                val sampleScale = LIQUID_GLASS_RESOLUTION_SCALE
                if (sampleScale > 0f) {
                    drawBackdrop()
                }
            },
            onDrawSurface = {
                drawRect(resolvedTint)
            }
        )
            .border(hairlineBorder, shape)
    } else {
        val fallbackTint = if (isObsidian) {
            palette.background.copy(alpha = 0.90f)
        } else {
            palette.glassTint.copy(
                alpha = maxOf(0.40f, fallbackAlphaTop.coerceAtMost(0.68f))
            )
        }
        this
            .clip(shape)
            .background(fallbackTint)
            .background(directionalSheen)
            .border(hairlineBorder, shape)
    }
}

/**
 * Neutral frosted glass capsule for active tab indicator inside the floating bottom nav bar.
 */
fun Modifier.sonoraLiquidGlassPill(
    shape: Shape,
    selected: Boolean
): Modifier = composed {
    if (!selected) return@composed this.clip(shape)
    val backdrop = LocalSonoraBackdrop.current
    val liquidEnabled = LocalLiquidGlassEnabled.current
    val isObsidian = LocalSonoraGlassStyle.current == GlassStyle.OBSIDIAN
    val directionalSheen = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.16f),
            Color.White.copy(alpha = 0.04f),
            Color.White.copy(alpha = 0.10f)
        )
    )
    val hairlineBorder = BorderStroke(LIQUID_GLASS_HAIRLINE_WIDTH, Color.White.copy(alpha = 0.10f))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && liquidEnabled && !isObsidian && backdrop != null) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(LIQUID_GLASS_BLUR_DP.toPx())
                if (shape is CornerBasedShape && size.minDimension > 1f) {
                    val maxRefractionPx = LIQUID_GLASS_MAX_REFRACTION_DP.toPx()
                    val heightPx = (size.minDimension * 0.5f)
                        .coerceAtMost(maxRefractionPx)
                        .coerceAtLeast(1f)
                    val amountPx = (heightPx * 0.5f)
                        .coerceAtMost(maxRefractionPx)
                        .coerceAtLeast(0.5f)
                    lens(
                        refractionHeight = heightPx,
                        refractionAmount = amountPx,
                        depthEffect = false
                    )
                }
            },
            highlight = {
                Highlight(
                    width = LIQUID_GLASS_HAIRLINE_WIDTH,
                    alpha = 0.28f,
                    style = HighlightStyle.Default()
                )
            },
            shadow = { null },
            onDrawSurface = {
                drawRect(Color.White.copy(alpha = 0.14f))
            }
        )
            .border(hairlineBorder, shape)
    } else {
        this
            .clip(shape)
            .background(Color.White.copy(alpha = 0.14f))
            .background(directionalSheen)
            .border(hairlineBorder, shape)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonoraLiquidModalSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = SonoraTextPrimary,
        dragHandle = null
    ) {
        CompositionLocalProvider(LocalSonoraBackdrop provides null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sonoraLiquidGlassSurface(
                        shape = sheetShape,
                        blurRadius = LIQUID_GLASS_BLUR_DP,
                        surfaceAlpha = 0.68f,
                        fallbackAlphaTop = 0.68f,
                        fallbackAlphaBottom = 0.68f
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.30f))
                    )
                }
                content()
            }
        }
    }
}
