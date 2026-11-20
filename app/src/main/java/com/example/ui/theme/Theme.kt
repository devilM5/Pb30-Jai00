package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FridayColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = CyberObsidian,
    primaryContainer = NeonBlueDim,
    onPrimaryContainer = NeonCyan,
    secondary = NeonCrimson,
    onSecondary = CyberTextPrimary,
    secondaryContainer = NeonCrimsonDim,
    onSecondaryContainer = NeonCrimsonBright,
    tertiary = CyberEmerald,
    onTertiary = CyberObsidian,
    background = CyberObsidian,
    onBackground = CyberTextPrimary,
    surface = CyberVoid,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurface,
    onSurfaceVariant = CyberTextSecondary,
    surfaceContainerLowest = CyberObsidian,
    surfaceContainerLow = CyberVoid,
    surfaceContainer = CyberSurface,
    surfaceContainerHigh = CyberSurfaceElevated,
    surfaceContainerHighest = CyberSurfaceHigh,
    outline = CyberOutline,
    outlineVariant = CyberOutlineGlow,
    error = NeonCrimson,
    onError = CyberTextPrimary
)

private val FridayShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun FridaySecurityTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FridayColorScheme,
        typography = FridayTypography,
        shapes = FridayShapes,
        content = content
    )
}
