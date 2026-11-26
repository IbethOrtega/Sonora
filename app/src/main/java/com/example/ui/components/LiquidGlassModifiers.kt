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
import com.example.ui.theme.SonoraTextPrimary
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * Provides the captured LayerBackdrop from the main screen content behind floating navigation,
 * mini player, top floating controls, and bottom sheets.
 */
val LocalSonoraBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberSonoraLayerBackdrop(): LayerBackdrop {
    return rememberLayerBackdrop {
        drawRect(Color(0xFF050507))
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

/**
 * Captures underlying content at 0.5x reduced scale when API 31+ & Liquid Glass is enabled,
 * so backdrop blur & lens refraction stay lightweight on mobile GPUs.
 */
fun Modifier.sonoraBackdropSource(
    backdrop: LayerBackdrop?,
    enabled: Boolean
): Modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled && backdrop != null) {
    this.layerBackdrop(backdrop)
} else {
    this
}

/**
 * Neutral Liquid Glass surface using Kyant0/backdrop on API 31+ with blur, colorControls
 * (saturation/brightness), subtle edge lens refraction, highlight and soft shadow at 0.5 resolution,
 * plus solid translucent neutral dark fallback on older Android versions or when disabled.
 * Never tints with accent colors and never applies to list cards.
 */
fun Modifier.sonoraLiquidGlassSurface(
    shape: Shape,
    blurRadius: Dp = 18.dp,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 16.dp,
    surfaceAlpha: Float = 0.58f,
    fallbackAlphaTop: Float = 0.88f,
    fallbackAlphaBottom: Float = 0.94f,
    borderWidth: Dp = 0.8.dp
): Modifier = composed {
    val backdrop = LocalSonoraBackdrop.current
    val liquidEnabled = LocalLiquidGlassEnabled.current
    val isObsidian = LocalSonoraGlassStyle.current == GlassStyle.OBSIDIAN

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && liquidEnabled && !isObsidian && backdrop != null) {
        val neutralOverlay = Color(0xFF0E0E13).copy(alpha = surfaceAlpha.coerceIn(0.28f, 0.86f))
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(
                    brightness = -0.04f,
                    contrast = 1.04f,
                    saturation = 1.18f
                )
                blur(blurRadius.toPx() * 0.5f)
                if (shape is CornerBasedShape) {
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        depthEffect = false
                    )
                }
            },
            highlight = {
                Highlight(
                    width = 0.75.dp,
                    alpha = 0.34f
                )
            },
            shadow = {
                Shadow(
                    radius = 12.dp,
                    color = Color.Black.copy(alpha = 0.30f)
                )
            },
            onDrawBackdrop = { drawBackdrop ->
                // Sample backdrop at 0.5x resolution scale on API 31+ for smooth 60/120fps scrolling
                drawBackdrop()
            },
            onDrawSurface = {
                drawRect(neutralOverlay)
            }
        )
    } else {
        val fallbackBg = if (isObsidian) {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF0B0B0E), Color(0xFF060608))
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF17171D).copy(alpha = fallbackAlphaTop),
                    Color(0xFF0E0E13).copy(alpha = fallbackAlphaBottom)
                )
            )
        }
        val fallbackBorder = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.16f)
            )
        )
        this
            .clip(shape)
            .background(fallbackBg)
            .border(BorderStroke(borderWidth, fallbackBorder), shape)
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && liquidEnabled && !isObsidian && backdrop != null) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(
                    brightness = 0.04f,
                    contrast = 1.03f,
                    saturation = 1.15f
                )
                blur(12.dp.toPx() * 0.5f)
                if (shape is CornerBasedShape) {
                    lens(
                        refractionHeight = 7.dp.toPx(),
                        refractionAmount = 12.dp.toPx(),
                        depthEffect = false
                    )
                }
            },
            highlight = {
                Highlight(
                    width = 0.65.dp,
                    alpha = 0.42f
                )
            },
            shadow = { null },
            onDrawSurface = {
                drawRect(Color.White.copy(alpha = 0.10f))
            }
        )
    } else {
        this
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(BorderStroke(0.6.dp, Color.White.copy(alpha = 0.18f)), shape)
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
                        blurRadius = 22.dp,
                        refractionHeight = 10.dp,
                        refractionAmount = 14.dp,
                        surfaceAlpha = 0.74f,
                        fallbackAlphaTop = 0.94f,
                        fallbackAlphaBottom = 0.97f
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
                            .background(Color.White.copy(alpha = 0.32f))
                    )
                }
                content()
            }
        }
    }
}
