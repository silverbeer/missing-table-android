package com.missingtable.scorer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The web's "Midnight Amber" palette (SB-144/146), so the phone and the web
 * are recognisably one product (SB-785).
 *
 * Values come from missing-table's tailwind.config.js (brand/accent ramps) and
 * style.css (semantic light/dark tokens). Keep them in step — a divergence
 * here is what made the two apps look unrelated in the first place.
 */
private object Brand {
    val B500 = Color(0xFF1E40AF) // primary navy
    val B600 = Color(0xFF1A3793) // header / hover
    val B700 = Color(0xFF152C75)
    val B800 = Color(0xFF112357)
    val B900 = Color(0xFF0C1838)
    val B100 = Color(0xFFD4E0F5)
    val B50 = Color(0xFFEEF3FB)
}

private object Accent {
    val A400 = Color(0xFFF59E0B) // accent amber
    val A600 = Color(0xFFB45309) // text-safe amber on LIGHT surfaces
    val A100 = Color(0xFFFDE8BF)
    val A900 = Color(0xFF45200A)
}

// style.css :root
private object LightToken {
    val surface = Color(0xFFF8FAFC)
    val surfaceAlt = Color(0xFFF1F5F9)
    val card = Color(0xFFFFFFFF)
    val fg = Color(0xFF0F172A)
    val fgMuted = Color(0xFF64748B)
    val line = Color(0xFFE2E8F0)
}

// style.css .dark
private object DarkToken {
    val surface = Color(0xFF0A0E1A)
    val surfaceAlt = Color(0xFF0E1424)
    val card = Color(0xFF131A2E)
    val fg = Color(0xFFE8EEF7)
    val fgMuted = Color(0xFF94A3B8)
    val line = Color(0xFF233047)
}

private val DarkColors = darkColorScheme(
    primary = Brand.B500,
    onPrimary = Color.White,
    // Chips and player tiles lean on primaryContainer; keep them brand navy
    // rather than Material's default purple-grey.
    primaryContainer = Brand.B800,
    onPrimaryContainer = Brand.B100,
    secondary = Accent.A400,
    onSecondary = Color.Black,
    tertiary = Accent.A400,
    // The update banner uses tertiaryContainer — amber on dark reads well.
    tertiaryContainer = Accent.A900,
    onTertiaryContainer = Accent.A100,
    background = DarkToken.surface,
    onBackground = DarkToken.fg,
    surface = DarkToken.surface,
    onSurface = DarkToken.fg,
    surfaceVariant = DarkToken.card,
    onSurfaceVariant = DarkToken.fgMuted,
    outline = DarkToken.line,
    // Sync-blocked banner and the LIVE marker. Kept deliberately loud: these
    // are the two things that must not be missed at a pitch.
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF4C1113),
    onErrorContainer = Color(0xFFFECACA),
)

private val LightColors = lightColorScheme(
    primary = Brand.B600,
    onPrimary = Color.White,
    primaryContainer = Brand.B50,
    onPrimaryContainer = Brand.B900,
    secondary = Brand.B500,
    onSecondary = Color.White,
    // A400 fails contrast as text on light surfaces — the web keeps A600 for
    // exactly this, so mirror that rather than reusing the accent everywhere.
    tertiary = Accent.A600,
    tertiaryContainer = Accent.A100,
    onTertiaryContainer = Accent.A900,
    background = LightToken.surface,
    onBackground = LightToken.fg,
    surface = LightToken.card,
    onSurface = LightToken.fg,
    surfaceVariant = LightToken.surfaceAlt,
    onSurfaceVariant = LightToken.fgMuted,
    outline = LightToken.line,
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** Connectivity dot on the live screen — semantic, so not part of the ramp. */
object StatusColors {
    val online = Color(0xFF16A34A)
    val offline = Color(0xFF94A3B8)
}

@Composable
fun MtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
