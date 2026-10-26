package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.jqssun.airplay.R

// Oriel Mirror: ink and violet, the same palette as the Oriel TV screens. Always dark.
private val Ink = Color(0xFF050506)
private val Glass = Color(0xFF0A0A0B)
private val Graphite = Color(0xFF141416)
private val Tile = Color(0xFF1C1C20)
private val Line = Color(0xFF26262A)
private val Outline = Color(0xFF3A3A40)
private val Paper = Color(0xFFF4F3EF)
private val Text2 = Color(0xFFB4B4BC)
private val Violet = Color(0xFF8B5CF6)
private val VioletHi = Color(0xFFA383FA)
private val VioletDeep = Color(0xFF2A2340)

private val OrielColors = darkColorScheme(
    primary = Violet,
    onPrimary = Ink,
    primaryContainer = VioletDeep,
    onPrimaryContainer = Paper,
    secondary = VioletHi,
    onSecondary = Ink,
    secondaryContainer = Tile,
    onSecondaryContainer = Paper,
    tertiary = VioletHi,
    onTertiary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Glass,
    onSurface = Paper,
    surfaceVariant = Graphite,
    onSurfaceVariant = Text2,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Glass,
    surfaceContainer = Graphite,
    surfaceContainerHigh = Tile,
    surfaceContainerHighest = Line,
    outline = Outline,
    outlineVariant = Line,
    error = Color(0xFFF87171),
    onError = Ink,
)

private val Geist = FontFamily(
    Font(R.font.geist_extralight, FontWeight.ExtraLight),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

private fun Typography.withFamily(f: FontFamily): Typography {
    fun TextStyle.g() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.g(), displayMedium = displayMedium.g(), displaySmall = displaySmall.g(),
        headlineLarge = headlineLarge.g().copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.g().copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.g().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.g().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.g().copy(fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.g().copy(fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.g(), bodyMedium = bodyMedium.g(), bodySmall = bodySmall.g(),
        labelLarge = labelLarge.g().copy(fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.g(), labelSmall = labelSmall.g(),
    )
}

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OrielColors, typography = Typography().withFamily(Geist), content = content)
}
