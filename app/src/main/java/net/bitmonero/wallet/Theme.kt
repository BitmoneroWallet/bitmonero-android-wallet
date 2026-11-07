package net.bitmonero.wallet

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// BitMonero brand. Orange sampled from the app's own logo mark
// (bmr_mark_clean.png from bitmonero-gui's appicon.icns) -- #F0631C.
// The surrounding palette mirrors the WATTx wallet's layered dark look
// (near-black canvas, raised cards) but tinted graphite/charcoal to match
// BitMonero rather than WATTx navy.
val BmrOrange = Color(0xFFF0631C)
val BmrOrangeDark = Color(0xFFC94E10)

/**
 * Extra brand colors the Material scheme doesn't have slots for (card
 * backgrounds, dimmed text, status greens). Screens read these directly so
 * the whole app shares one palette.
 */
object Brand {
    val canvas = Color(0xFF0E0F13)       // app background (near-black)
    val card = Color(0xFF1A1C22)         // raised card surface
    val cardAlt = Color(0xFF232630)      // nested / chip surface
    val line = Color(0xFF2C2F3A)         // hairline borders/dividers
    val text = Color(0xFFF4F5F7)         // primary text
    val textDim = Color(0xFF9BA0AE)      // secondary text
    val orange = BmrOrange
    val green = Color(0xFF3DDC84)        // connected / success
    val red = Color(0xFFFF6E5A)          // error / disconnected
    val amber = Color(0xFFFFC24B)        // syncing / warning
}

private val BmrDarkColors = darkColorScheme(
    primary = BmrOrange,
    onPrimary = Color.White,
    secondary = BmrOrangeDark,
    onSecondary = Color.White,
    background = Brand.canvas,
    onBackground = Brand.text,
    surface = Brand.card,
    onSurface = Brand.text,
    surfaceVariant = Brand.cardAlt,
    onSurfaceVariant = Brand.textDim,
    outline = Brand.line,
    error = Brand.red,
    onError = Color.White,
)

/**
 * Always dark -- like the WATTx wallet, the app ships a single intentional
 * dark theme rather than following the system light/dark toggle (a half-lit
 * wallet looked unfinished).
 */
@Composable
fun BitMoneroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BmrDarkColors, content = content)
}
