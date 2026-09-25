package com.maogig.gigreader.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maogig.gigreader.core.model.ThemeMode

/*
 * GigReader's own visual identity: a quiet "reading room" palette — warm paper neutrals, ink-blue
 * primary, a leather-brown secondary and an amber accent reserved for highlights and progress.
 * Few colors, tonal surfaces instead of shadows, no gradients.
 */

private val Ink = Color(0xFF2E4A6B)
private val InkLight = Color(0xFFA9C4E4)
private val Leather = Color(0xFF8A5A3B)
private val LeatherLight = Color(0xFFE2B99A)
private val Amber = Color(0xFFD99A1E)
private val AmberLight = Color(0xFFF2C766)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F4),
    onPrimaryContainer = Color(0xFF0E2238),
    secondary = Leather,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E1D4),
    onSecondaryContainer = Color(0xFF34190A),
    tertiary = Amber,
    onTertiary = Color(0xFF2B1C00),
    tertiaryContainer = Color(0xFFFCE7B8),
    onTertiaryContainer = Color(0xFF2B1C00),
    background = Color(0xFFF8F6F2),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFF8F6F2),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE9E5DE),
    onSurfaceVariant = Color(0xFF4B4741),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F0EB),
    surfaceContainer = Color(0xFFEEEAE4),
    surfaceContainerHigh = Color(0xFFE8E4DD),
    surfaceContainerHighest = Color(0xFFE2DDD6),
    outline = Color(0xFF7C776F),
    outlineVariant = Color(0xFFCFC9C0),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = InkLight,
    onPrimary = Color(0xFF0E2238),
    primaryContainer = Color(0xFF28405C),
    onPrimaryContainer = Color(0xFFD6E3F4),
    secondary = LeatherLight,
    onSecondary = Color(0xFF3F2211),
    secondaryContainer = Color(0xFF5A3A25),
    onSecondaryContainer = Color(0xFFF3E1D4),
    tertiary = AmberLight,
    onTertiary = Color(0xFF3F2C00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFCE7B8),
    background = Color(0xFF131416),
    onBackground = Color(0xFFE6E2DC),
    surface = Color(0xFF131416),
    onSurface = Color(0xFFE6E2DC),
    surfaceVariant = Color(0xFF3A3834),
    onSurfaceVariant = Color(0xFFCBC5BC),
    surfaceContainerLowest = Color(0xFF0E0F10),
    surfaceContainerLow = Color(0xFF1A1B1D),
    surfaceContainer = Color(0xFF1F2022),
    surfaceContainerHigh = Color(0xFF292A2D),
    surfaceContainerHighest = Color(0xFF343538),
    outline = Color(0xFF958F86),
    outlineVariant = Color(0xFF4A4741),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/** True-black surfaces: pixels off on OLED panels, the lowest-power way to show the UI. */
private val AmoledColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF111113),
    surfaceContainerHigh = Color(0xFF18191B),
    surfaceContainerHighest = Color(0xFF202124),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** App-wide UI preferences that components need without threading them through every call. */
@Immutable
data class UiPreferences(
    /** When false, components must skip decorative animations (instant state changes). */
    val animationsEnabled: Boolean = true,
    val isDark: Boolean = false,
)

val LocalUiPreferences = staticCompositionLocalOf { UiPreferences() }

@Composable
fun GigReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors: ColorScheme = when (themeMode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.AMOLED -> AmoledColors
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
    }
    val isDark = colors !== LightColors
    CompositionLocalProvider(LocalUiPreferences provides UiPreferences(animationsEnabled, isDark)) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Monospace-free numeric style for page counters ("12 / 184"): tabular figures avoid jitter. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")
