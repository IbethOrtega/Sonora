package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.AccentPreset
import com.example.model.GlassStyle

val LocalSonoraAccent = staticCompositionLocalOf { SonoraCrimson }
val LocalSonoraAccentSecondary = staticCompositionLocalOf { SonoraPlum }
val LocalSonoraGlassStyle = staticCompositionLocalOf { GlassStyle.LIQUID }
val LocalSonoraPalette = staticCompositionLocalOf { computeTintedBlackPalette(SonoraCrimson) }

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
    val palette = remember(primary) { computeTintedBlackPalette(primary) }

    val colorScheme = darkColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = palette.elevated,
        onPrimaryContainer = palette.textPrimary,
        secondary = primary.copy(alpha = 0.85f),
        onSecondary = Color.White,
        secondaryContainer = palette.elevated,
        onSecondaryContainer = palette.textPrimary,
        tertiary = palette.pressed,
        onTertiary = palette.textPrimary,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.elevated,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.hairline,
        outlineVariant = palette.hairline
    )

    CompositionLocalProvider(
        LocalSonoraAccent provides primary,
        LocalSonoraAccentSecondary provides palette.elevated,
        LocalSonoraGlassStyle provides glassStyle,
        LocalSonoraPalette provides palette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SonoraTypography,
            shapes = SonoraShapes,
            content = content
        )
    }
}
