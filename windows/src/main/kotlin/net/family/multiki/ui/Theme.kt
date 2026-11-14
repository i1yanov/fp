package net.family.multiki.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette of a Soviet film poster: aged paper, banner red, ink brown, mustard, sea green. */
object Palette {
    val Paper = Color(0xFFF3E6C8)
    val PaperDark = Color(0xFFE6D3AA)
    val Red = Color(0xFF9E2B25)
    val RedDark = Color(0xFF6E1B17)
    val Ink = Color(0xFF3B2A20)
    val InkSoft = Color(0xFF6B5444)
    val Mustard = Color(0xFFD9A441)
    val Teal = Color(0xFF2F5D62)
    val Film = Color(0xFF2A1E17)
}

val Oswald = FontFamily(
    Font("fonts/oswald_regular.ttf", FontWeight.Normal),
    Font("fonts/oswald_bold.ttf", FontWeight.Bold),
)
val PtSerif = FontFamily(
    Font("fonts/ptserif_regular.ttf", FontWeight.Normal),
    Font("fonts/ptserif_bold.ttf", FontWeight.Bold),
)
val PtSansNarrow = FontFamily(
    Font("fonts/ptsansnarrow_regular.ttf", FontWeight.Normal),
    Font("fonts/ptsansnarrow_bold.ttf", FontWeight.Bold),
)

private val colors = lightColorScheme(
    primary = Palette.Red,
    onPrimary = Palette.Paper,
    secondary = Palette.Teal,
    onSecondary = Palette.Paper,
    tertiary = Palette.Mustard,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.PaperDark,
    onSurfaceVariant = Palette.InkSoft,
    outline = Palette.InkSoft,
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 1.sp),
    headlineSmall = TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = PtSansNarrow, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = PtSansNarrow, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = PtSerif, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = PtSerif, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = PtSerif, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = PtSansNarrow, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = PtSansNarrow, fontSize = 12.sp),
)

@Composable
fun MultikiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
