package com.bandori.pet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFB32666),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E8),
    onPrimaryContainer = Color(0xFF3D0020),
    secondary = Color(0xFF715763),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8E8),
    tertiary = Color(0xFF9A452D),
    tertiaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFFFFF8FB),
    onBackground = Color(0xFF201A1D),
    surface = Color(0xFFFFF8FB),
    onSurface = Color(0xFF201A1D),
    surfaceVariant = Color(0xFFF2DDE6),
    onSurfaceVariant = Color(0xFF51434A),
    outline = Color(0xFF83737A),
    outlineVariant = Color(0xFFD5C2CB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9AC7),
    onPrimary = Color(0xFF5B0034),
    primaryContainer = Color(0xFF8E0054),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFFD7B7C8),
    onSecondary = Color(0xFF3E2B35),
    secondaryContainer = Color(0xFF59414D),
    tertiary = Color(0xFFFFB4A2),
    tertiaryContainer = Color(0xFF7C2E1E),
    background = Color(0xFF1A0D14),
    onBackground = Color(0xFFF2DEE7),
    surface = Color(0xFF1A0D14),
    onSurface = Color(0xFFF2DEE7),
    surfaceVariant = Color(0xFF51434A),
    onSurfaceVariant = Color(0xFFD5C2CB),
    outline = Color(0xFF9E8993),
    outlineVariant = Color(0xFF51434A),
)

@Composable
fun BandoriPetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
