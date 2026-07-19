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
    secondary = Accent,
    tertiary = Accent,
)

private val LightColors = lightColorScheme(
    primary = Pitch,
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
