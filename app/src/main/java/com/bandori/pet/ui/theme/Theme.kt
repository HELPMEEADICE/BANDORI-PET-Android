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
    secondaryContainer = Color(0xFFFFD8E8),
    background = Color(0xFFFFF8FB),
    surface = Color(0xFFFFF8FB),
    surfaceVariant = Color(0xFFF2DDE6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1CF),
    onPrimary = Color(0xFF640035),
    primaryContainer = Color(0xFF8F004D),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFFE0BDCA),
    secondaryContainer = Color(0xFF58404B),
    background = Color(0xFF1F1117),
    surface = Color(0xFF1F1117),
    surfaceVariant = Color(0xFF51434A),
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
