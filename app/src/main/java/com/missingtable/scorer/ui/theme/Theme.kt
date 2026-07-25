package com.missingtable.scorer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pitch = Color(0xFF0D3B2E)
private val PitchLight = Color(0xFF2E7D5B)
private val Accent = Color(0xFFFFC107)

private val DarkColors = darkColorScheme(
    primary = PitchLight,
    onPrimary = Color.White,
    // Player tiles / chips lean on primaryContainer — keep them pitch-green
    // in the dark instead of Material's default muddy purple-grey.
    primaryContainer = Color(0xFF1B4D3A),
    onPrimaryContainer = Color(0xFFBDE5D2),
    secondary = Accent,
    onSecondary = Color.Black,
    tertiary = Accent,
    tertiaryContainer = Color(0xFF4D3F00),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF111412),
    surface = Color(0xFF111412),
)

private val LightColors = lightColorScheme(
    primary = Pitch,
    primaryContainer = Color(0xFFB8E5CE),
    onPrimaryContainer = Pitch,
    secondary = PitchLight,
    tertiary = Accent,
)

@Composable
fun MtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
