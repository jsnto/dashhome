package com.jsnto.dashhome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The dashhome palettes, mirroring the web dashboard's CSS variables
 * (frontend/src/index.css): the dark scheme matches the default (dark)
 * variables and the light scheme the `data-theme='light'` override.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CC2FF), // --accent
    onPrimary = Color(0xFF04283C),
    secondary = Color(0xFF8DE04C), // --good
    onSecondary = Color(0xFF1D3307),
    tertiary = Color(0xFFC792EA),
    onTertiary = Color(0xFF2B1240),
    background = Color(0xFF0B1220), // the app's window background (--bg: #0f1420)
    onBackground = Color(0xFFE6EBF5), // --text
    surface = Color(0xFF101A2C), // the cards' surface (the dark --bg-card family)
    onSurface = Color(0xFFE6EBF5), // --text
    surfaceVariant = Color(0xFF1B2A44), // --bg-card-2 family
    onSurfaceVariant = Color(0xFF93A0B8), // --text-dim
    outline = Color(0xFF31445F), // --border family
    error = Color(0xFFFF6B81), // --err
    onError = Color(0xFF3B0A12),
)

/**
 * The light palette, matching the web's light CSS variables
 * (`:root[data-theme='light']` in frontend/src/index.css): the same
 * roles, light values.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A7DC2), // --accent
    onPrimary = Color(0xFFF2F6FD),
    secondary = Color(0xFF2E9E44), // --good
    onSecondary = Color(0xFF0A2E12),
    tertiary = Color(0xFFA8620A), // --warn
    onTertiary = Color(0xFFFBEFE0),
    background = Color(0xFFF2F5FA), // --bg
    onBackground = Color(0xFF1D2537), // --text
    surface = Color(0xFFFFFFFF), // --bg-card
    onSurface = Color(0xFF1D2537), // --text
    surfaceVariant = Color(0xFFEEF2F9), // --bg-card-2
    onSurfaceVariant = Color(0xFF5B6A84), // --text-dim
    outline = Color(0xFFD3DBE9), // --border
    error = Color(0xFFD63B56), // --err
    onError = Color(0xFF40101A),
)

/** The theme palettes by scheme (the web's two `data-theme` palettes). */
fun dashhomeColorScheme(dark: Boolean) = if (dark) DarkColors else LightColors

/**
 * The effective dark flag (the web's initialTheme): the user's explicit
 * persisted choice wins; while the user has never picked one, the OS
 * preference applies (and is followed live: a system light/dark change
 * recreates the activity, which re-resolves the theme).
 */
fun effectiveTheme(stored: String?, systemDark: Boolean): Boolean =
    when (stored) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

@Composable
fun DashhomeTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = dashhomeColorScheme(dark), content = content)
}
