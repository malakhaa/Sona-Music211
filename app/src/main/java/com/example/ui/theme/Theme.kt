package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SonaDarkColorScheme = darkColorScheme(
    primary = SonaCyan,
    onPrimary = SonaObsidianBg,
    primaryContainer = SonaElevatedSurface,
    onPrimaryContainer = SonaCyanGlow,
    secondary = SonaAmber,
    onSecondary = SonaObsidianBg,
    secondaryContainer = SonaElevatedSurface,
    onSecondaryContainer = SonaAmber,
    tertiary = SonaEmerald,
    onTertiary = SonaObsidianBg,
    background = SonaObsidianBg,
    onBackground = SonaTextPrimary,
    surface = SonaMidnightSurface,
    onSurface = SonaTextPrimary,
    surfaceVariant = SonaElevatedSurface,
    onSurfaceVariant = SonaTextSecondary,
    outline = SonaGlassBorder,
    error = SonaRose
)

val SonaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun SonaMusicTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SonaDarkColorScheme,
        typography = SonaTypography,
        shapes = SonaShapes,
        content = content
    )
}
