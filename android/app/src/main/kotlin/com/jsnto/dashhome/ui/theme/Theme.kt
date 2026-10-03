package com.jsnto.dashhome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The dashboard is a dark surface on the web; the app matches it instead
 * of chasing the OS light/dark preference (v1: one theme).
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CC2FF),
    onPrimary = Color(0xFF04283C),
    secondary = Color(0xFF8DE04C),
    onSecondary = Color(0xFF1D3307),
    tertiary = Color(0xFFC792EA),
    onTertiary = Color(0xFF2B1240),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFDCE7F5),
    surface = Color(0xFF101A2C),
    onSurface = Color(0xFFDCE7F5),
    surfaceVariant = Color(0xFF1B2A44),
    onSurfaceVariant = Color(0xFF9FB3CC),
    outline = Color(0xFF31445F),
    error = Color(0xFFFF6B81),
    onError = Color(0xFF3B0A12),
)

@Composable
fun DashhomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
