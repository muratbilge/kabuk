package app.kabuk.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.kabuk.R

val Mono = FontFamily(Font(R.font.jetbrains_mono))

// Brand: the nautilus logo. Peach = primary, Tokyo-Night blue = secondary, logo pink = tertiary/attention.
val Bg = Color(0xFF0E1116)          // surface, same as the terminal background
val Surface1 = Color(0xFF161A22)    // surfaceContainer: tab strip, key bar, message row
val Surface2 = Color(0xFF1F2430)    // surfaceContainerHigh: fields, chips, banners
val KeyFill = Color(0xFF272D3B)     // keys stand out from the bar
val Accent = Color(0xFFFFC48A)      // primary
val OnAccent = Color(0xFF4A2800)
val Blue = Color(0xFF7AA2F7)        // secondary
val Muted = Color(0xFF8C96AD)       // supporting text and icons (≥ 5:1 on every surface)
val Text1 = Color(0xFFE6E9EF)
val Good = Color(0xFF9ECE6A)
val Warn = Color(0xFFE0AF68)
val Bad = Color(0xFFF7768E)

private val KabukDark = darkColorScheme(
    primary = Accent, onPrimary = OnAccent,
    primaryContainer = Color(0xFF6B4019), onPrimaryContainer = Color(0xFFFFDCBE),
    inversePrimary = Color(0xFF8A5A2B), surfaceTint = Accent,
    secondary = Blue, onSecondary = Color(0xFF0A1F4D),
    secondaryContainer = Color(0xFF2A3B66), onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Bad, onTertiary = Color(0xFF5C0F22),
    tertiaryContainer = Color(0xFF6E2335), onTertiaryContainer = Color(0xFFFFD9DF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1F2B), onErrorContainer = Color(0xFFFFDAD6),
    background = Bg, onBackground = Text1,
    surface = Bg, onSurface = Text1,
    surfaceVariant = Color(0xFF2A2F3B), onSurfaceVariant = Color(0xFFB4BCD0),
    surfaceDim = Bg, surfaceBright = Color(0xFF30343F),
    surfaceContainerLowest = Color(0xFF080A0E), surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Surface1, surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF2A2F3B),
    outline = Color(0xFF6B7591), outlineVariant = Color(0xFF2E3440),
    inverseSurface = Text1, inverseOnSurface = Surface2,
    scrim = Color(0xFF000000),
)

// Line heights relative to the font size, so a Text(fontSize = 13.sp) doesn't keep a 24sp line height.
private val base = Typography()
private fun TextStyle.rel(lh: Double) = copy(lineHeight = lh.em)
private val KabukTypography = Typography(
    displayLarge = base.displayLarge, displayMedium = base.displayMedium, displaySmall = base.displaySmall,
    headlineLarge = base.headlineLarge.rel(1.25), headlineMedium = base.headlineMedium.rel(1.28),
    headlineSmall = base.headlineSmall.rel(1.33),
    titleLarge = base.titleLarge.rel(1.27), titleMedium = base.titleMedium.rel(1.5), titleSmall = base.titleSmall.rel(1.43),
    bodyLarge = base.bodyLarge.rel(1.5), bodyMedium = base.bodyMedium.rel(1.43), bodySmall = base.bodySmall.rel(1.33),
    labelLarge = base.labelLarge.rel(1.43), labelMedium = base.labelMedium.rel(1.33), labelSmall = base.labelSmall.rel(1.45),
)

private val KabukShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KabukTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KabukDark, typography = KabukTypography, shapes = KabukShapes) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Text1, content = content,
        )
    }
}

/** one snackbar host for the whole app (messages come from background threads via TabManager.toast) */
object Snack {
    val host = androidx.compose.material3.SnackbarHostState()
}
