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

/**
 * The app's colours, which are the product's own theme rather than a second set.
 *
 * The palette is the icon's - crimson brackets, a gold glyph, printed ink on paper - and it is
 * declared once, on the surface page, as the "phonetix" theme. Material's scheme is built from
 * it here, so a Material button and a control drawn from [Tokens] are the same colour and a
 * colour changed on that page moves both.
 */
private val PALETTE_LIGHT = Tokens.palette(Tokens.Theme.PHONETIX, dark = false)
private val PALETTE_DARK = Tokens.palette(Tokens.Theme.PHONETIX, dark = true)

/** Crimson is the mark's, not a role any surface names: it is read off the palette's danger. */
private fun scheme(p: Tokens.Palette, dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = Color(p.accent),
            onPrimary = Color(p.accentInk),
            primaryContainer = Color(p.accentBg),
            onPrimaryContainer = Color(p.accent),
            secondary = Color(p.danger),
            onSecondary = Color(p.accentInk),
            background = Color(p.pageBg),
            onBackground = Color(p.ink),
            surface = Color(p.surface),
            onSurface = Color(p.ink),
            surfaceVariant = Color(p.surfaceRaised),
            onSurfaceVariant = Color(p.inkMuted),
            outline = Color(p.border),
            outlineVariant = Color(p.border),
            error = Color(p.danger),
        )
    } else {
        lightColorScheme(
            primary = Color(p.accent),
            onPrimary = Color(p.accentInk),
            primaryContainer = Color(p.accentBg),
            onPrimaryContainer = Color(p.accent),
            secondary = Color(p.danger),
            onSecondary = Color(p.accentInk),
            background = Color(p.pageBg),
            onBackground = Color(p.ink),
            surface = Color(p.surface),
            onSurface = Color(p.ink),
            surfaceVariant = Color(p.surfaceRaised),
            onSurfaceVariant = Color(p.inkMuted),
            outline = Color(p.border),
            outlineVariant = Color(p.border),
            error = Color(p.danger),
        )
    }

/** The colours a screen draws its own controls in, which are the same ones Material got. */
@Composable
fun appPalette(): Tokens.Palette = if (isSystemInDarkTheme()) PALETTE_DARK else PALETTE_LIGHT

/** Brand colours the screens reach for directly. */
object Brand {
    val crimson: Color @Composable get() = Color(appPalette().danger)
    val gold: Color @Composable get() = Color(appPalette().accent)
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
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = scheme(if (dark) PALETTE_DARK else PALETTE_LIGHT, dark),
        typography = AppTypography,
        content = content,
    )
}
