package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The palette is the icon's: crimson brackets, a gold glyph, printed ink on paper. Warm
// neutrals rather than the usual blue-grey, so a screen of transcriptions reads like a
// page rather than a dashboard.
private val Crimson = Color(0xFFDC2626)
private val CrimsonSoft = Color(0xFFE5484D)
private val Gold = Color(0xFFFBBF24)
private val GoldDeep = Color(0xFFB45309)
private val Ink = Color(0xFF1B1410)
private val InkRaised = Color(0xFF241C17)
private val InkLine = Color(0xFF3A2E25)
private val Paper = Color(0xFFF7EFDD)
private val PaperRaised = Color(0xFFFFFBF2)
private val PaperLine = Color(0xFFE3D7BE)
private val InkText = Color(0xFF2B2117)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = InkLine,
    onPrimaryContainer = Gold,
    secondary = CrimsonSoft,
    onSecondary = Color.White,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFC9B79C),
    outline = InkLine,
    outlineVariant = InkLine,
    error = CrimsonSoft,
)

private val LightColors = lightColorScheme(
    primary = GoldDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDF0D2),
    onPrimaryContainer = GoldDeep,
    secondary = Crimson,
    onSecondary = Color.White,
    background = Paper,
    onBackground = InkText,
    surface = Paper,
    onSurface = InkText,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = Color(0xFF6B5B45),
    outline = PaperLine,
    outlineVariant = PaperLine,
    error = Crimson,
)

/** Brand colours the screens reach for directly. */
object Brand {
    val crimson: Color @Composable get() = if (isSystemInDarkTheme()) CrimsonSoft else Crimson
    val gold: Color @Composable get() = if (isSystemInDarkTheme()) Gold else GoldDeep
}

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** The serif the transcriptions and the wordmark are set in, matching the icon. */
val IpaStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)

@Composable
fun PhonetixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
