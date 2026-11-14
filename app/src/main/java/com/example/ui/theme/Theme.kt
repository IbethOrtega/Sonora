package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SonoraColorScheme = darkColorScheme(
    primary = SonoraCrimson,
    onPrimary = Color.White,
    primaryContainer = SonoraPlum,
    onPrimaryContainer = Color.White,
    secondary = SonoraCoral,
    onSecondary = Color.White,
    secondaryContainer = SonoraSurfaceElevated,
    onSecondaryContainer = SonoraTextPrimary,
    tertiary = SonoraViolet,
    onTertiary = Color.White,
    background = SonoraBackground,
    onBackground = SonoraTextPrimary,
    surface = SonoraSurface,
    onSurface = SonoraTextPrimary,
    surfaceVariant = SonoraSurfaceElevated,
    onSurfaceVariant = SonoraTextSecondary,
    outline = SonoraGlassBorderTop,
    outlineVariant = SonoraGlassBorderBottom
)

val SonoraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SonoraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SonoraColorScheme,
        typography = SonoraTypography,
        shapes = SonoraShapes,
        content = content
    )
}
