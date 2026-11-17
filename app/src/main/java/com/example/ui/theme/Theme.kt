package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.example.model.AccentPreset
import com.example.model.GlassStyle

val LocalSonoraAccent = staticCompositionLocalOf { SonoraCrimson }
val LocalSonoraAccentSecondary = staticCompositionLocalOf { SonoraPlum }
val LocalSonoraGlassStyle = staticCompositionLocalOf { GlassStyle.LIQUID }

val SonoraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SonoraTheme(
    accentPreset: AccentPreset = AccentPreset.CRIMSON,
    customAccentHex: Long? = null,
    glassStyle: GlassStyle = GlassStyle.LIQUID,
    content: @Composable () -> Unit
) {
    val primary = customAccentHex?.let { Color(it) } ?: Color(accentPreset.primaryHex)
    val secondaryContainer = if (customAccentHex != null) {
        lerp(primary, Color(0xFF0E0E16), 0.74f)
    } else {
        Color(accentPreset.secondaryHex)
    }
    val bgColor = if (glassStyle == GlassStyle.OBSIDIAN) Color(0xFF040406) else SonoraBackground
    val surfaceColor = if (glassStyle == GlassStyle.OBSIDIAN) Color(0xFF0B0B10) else SonoraSurface

    val colorScheme = darkColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = secondaryContainer,
        onPrimaryContainer = Color.White,
        secondary = primary.copy(alpha = 0.85f),
        onSecondary = Color.White,
        secondaryContainer = SonoraSurfaceElevated,
        onSecondaryContainer = SonoraTextPrimary,
        tertiary = SonoraViolet,
        onTertiary = Color.White,
        background = bgColor,
        onBackground = SonoraTextPrimary,
        surface = surfaceColor,
        onSurface = SonoraTextPrimary,
        surfaceVariant = SonoraSurfaceElevated,
        onSurfaceVariant = SonoraTextSecondary,
        outline = SonoraGlassBorderTop,
        outlineVariant = SonoraGlassBorderBottom
    )

    CompositionLocalProvider(
        LocalSonoraAccent provides primary,
        LocalSonoraAccentSecondary provides secondaryContainer,
        LocalSonoraGlassStyle provides glassStyle
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SonoraTypography,
            shapes = SonoraShapes,
            content = content
        )
    }
}
