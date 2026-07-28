package com.bandori.pet.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFFB32666),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E8),
    onPrimaryContainer = Color(0xFF3D0020),
    secondary = Color(0xFF715763),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDD9E8),
    onSecondaryContainer = Color(0xFF291520),
    tertiary = Color(0xFF9A452D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3A0A00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8FB),
    onBackground = Color(0xFF201A1D),
    surface = Color(0xFFFFF8FB),
    onSurface = Color(0xFF201A1D),
    surfaceVariant = Color(0xFFF2DDE6),
    onSurfaceVariant = Color(0xFF51434A),
    surfaceTint = Color(0xFFB32666),
    inverseSurface = Color(0xFF352F32),
    inverseOnSurface = Color(0xFFFBEFF3),
    inversePrimary = Color(0xFFFFAFD0),
    outline = Color(0xFF83737A),
    outlineVariant = Color(0xFFD5C2CB),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFF8FB),
    surfaceDim = Color(0xFFE9DFE3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0F5),
    surfaceContainer = Color(0xFFFBEAF1),
    surfaceContainerHigh = Color(0xFFF5E4EB),
    surfaceContainerHighest = Color(0xFFEFDEE5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9AC7),
    onPrimary = Color(0xFF5B0034),
    primaryContainer = Color(0xFF8E0054),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFFD7B7C8),
    onSecondary = Color(0xFF3E2B35),
    secondaryContainer = Color(0xFF59414D),
    onSecondaryContainer = Color(0xFFF4D3E4),
    tertiary = Color(0xFFFFB4A2),
    onTertiary = Color(0xFF5C190B),
    tertiaryContainer = Color(0xFF7C2E1E),
    onTertiaryContainer = Color(0xFFFFDAD0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A0D14),
    onBackground = Color(0xFFF2DEE7),
    surface = Color(0xFF1A0D14),
    onSurface = Color(0xFFF2DEE7),
    surfaceVariant = Color(0xFF51434A),
    onSurfaceVariant = Color(0xFFD5C2CB),
    surfaceTint = Color(0xFFFF9AC7),
    inverseSurface = Color(0xFFF2DEE7),
    inverseOnSurface = Color(0xFF352F32),
    inversePrimary = Color(0xFFB32666),
    outline = Color(0xFF9E8993),
    outlineVariant = Color(0xFF51434A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF433039),
    surfaceDim = Color(0xFF1A0D14),
    surfaceContainerLowest = Color(0xFF15080F),
    surfaceContainerLow = Color(0xFF23151C),
    surfaceContainer = Color(0xFF281921),
    surfaceContainerHigh = Color(0xFF33232B),
    surfaceContainerHighest = Color(0xFF3E2E36),
)

private val BaseTypography = Typography()

private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displayMedium = BaseTypography.displayMedium.copy(fontWeight = FontWeight.Bold),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun BandoriPetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }
    SystemBarAppearance(darkTheme)
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

@Composable
private fun SystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
