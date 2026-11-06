package net.bitmonero.wallet

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Sampled directly from the app's own logo mark (bmr_mark_clean.png,
// extracted from bitmonero-gui's appicon.icns) rather than guessed --
// #F0631C is its orange, #4E4E4E its dark grey.
private val BitMoneroOrange = Color(0xFFF0631C)
private val LogoGrey = Color(0xFF4E4E4E)

private val DarkColors = darkColorScheme(
    primary = BitMoneroOrange,
    onPrimary = Color.White,
    secondary = LogoGrey,
    onSecondary = Color.White,
    background = Color(0xFF1F1F1F), // dark grey, darkened from the logo's #4E4E4E for actual readable contrast
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF2A2A2A),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = LogoGrey,
    error = Color(0xFFFF6E5A)
)

private val LightColors = lightColorScheme(
    primary = BitMoneroOrange,
    onPrimary = Color.White,
    secondary = LogoGrey,
    onSecondary = Color.White,
    background = Color(0xFFFFFBFA),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF0E5E0),
    error = Color(0xFFBA1A1A)
)

/** Follows the system light/dark setting -- no in-app toggle requested, so this is the whole story. */
@Composable
fun BitMoneroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
