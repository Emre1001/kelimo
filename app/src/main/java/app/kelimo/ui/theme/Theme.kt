package app.kelimo.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import app.kelimo.data.ThemeMode

/** Semantic palette. Few colors, used consistently; no gradients for decoration. */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val good: Color,
    val goodSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val warm: Color,
    val warmSoft: Color,
    /** German article colors: der / die / das. */
    val der: Color,
    val die: Color,
    val das: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFF5F3EE), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEDEAE3), line = Color(0x1A15171C),
    ink = Color(0xFF15171C), ink2 = Color(0xFF55585F), ink3 = Color(0xFF8D9097),
    accent = Color(0xFF2E5BE6), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFE3E9FC),
    good = Color(0xFF178A5B), goodSoft = Color(0xFFDDF2E7), bad = Color(0xFFD1443B), badSoft = Color(0xFFFBE3E0),
    warm = Color(0xFFE59A1A), warmSoft = Color(0xFFFCEFD6),
    der = Color(0xFF2E5BE6), die = Color(0xFFD1443B), das = Color(0xFF178A5B),
    dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF0D0E11), surface = Color(0xFF17191E), surface2 = Color(0xFF22252B), line = Color(0x1FF2F1EC),
    ink = Color(0xFFF2F1EC), ink2 = Color(0xFFB0B2B8), ink3 = Color(0xFF7C7F86),
    accent = Color(0xFF6C8CFF), accentInk = Color(0xFF0D0E11), accentSoft = Color(0xFF1F2747),
    good = Color(0xFF3CC48A), goodSoft = Color(0xFF15322A), bad = Color(0xFFFF6B60), badSoft = Color(0xFF3A1D1B),
    warm = Color(0xFFFFB84D), warmSoft = Color(0xFF3A2C14),
    der = Color(0xFF7C98FF), die = Color(0xFFFF7A70), das = Color(0xFF4CD39A),
    dark = true,
)

@Immutable
data class Typo(
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

private val family = FontFamily.Default

val Type = Typo(
    display = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    title = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    headline = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    label = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    caption = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

/**
 * Motion tokens. Critically damped springs for layout (no wobble, no overshoot, interruptible),
 * a lightly underdamped spring for touch feedback.
 */
object Motion {
    fun <T> layout() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
    fun <T> quick() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow * 2.5f)
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = 900f)
    fun <T> gentle() = spring<T>(dampingRatio = 0.85f, stiffness = 220f)
    val offset = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalReduceMotion = staticCompositionLocalOf { false }

object K {
    val c: Palette @Composable get() = LocalPalette.current
    val t: Typo get() = Type
}

@Composable
fun KelimoTheme(mode: ThemeMode, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(primary = p.accent, onPrimary = p.accentInk, background = p.bg, surface = p.surface, onSurface = p.ink, onBackground = p.ink, error = p.bad)
    else lightColorScheme(primary = p.accent, onPrimary = p.accentInk, background = p.bg, surface = p.surface, onSurface = p.ink, onBackground = p.ink, error = p.bad)
    CompositionLocalProvider(LocalPalette provides p, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
