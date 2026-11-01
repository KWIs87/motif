package com.kirkisham.motif.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kirkisham.motif.data.Appearance

/** Motif palette, matching the wireframes: near-black ground, warm off-white text, one gold accent. */
object MotifColors {
    val Ground = Color(0xFF121212)
    val Surface = Color(0xFF1A1A1A)
    val Line = Color(0xFF1E1E1E)
    val Text = Color(0xFFE8E6E1)
    val TextMuted = Color(0xFF8A8882)
    val Accent = Color(0xFFC9A96E)
}

/** Colors derived from the Appearance settings so a custom text/background keeps the same contrast steps. */
data class MotifPalette(
    val ground: Color,
    val surface: Color,
    val card: Color,
    val line: Color,
    /** Border of unselected chips and outlined buttons. */
    val chipLine: Color,
    val text: Color,
    val textMuted: Color,
    val textDim: Color,
    val accent: Color,
    val glow: Color,
    /** 0f..1f */
    val glowIntensity: Float,
    val textScale: Float,
) {
    companion object {
        fun from(a: Appearance): MotifPalette {
            val text = Color(a.textColor)
            val ground = Color(a.backgroundColor)
            val lightBg = ground.luminance() > 0.5f
            val step = { f: Float -> lerp(ground, text, f) }
            return MotifPalette(
                ground = ground,
                surface = step(if (lightBg) 0.04f else 0.035f),
                card = step(if (lightBg) 0.07f else 0.055f),
                line = step(0.06f),
                chipLine = step(0.16f),
                text = text,
                textMuted = lerp(ground, text, 0.55f),
                textDim = lerp(ground, text, 0.32f),
                accent = MotifColors.Accent,
                glow = Color(a.glowColor),
                glowIntensity = (a.glowIntensity.coerceIn(0, 100)) / 100f,
                textScale = a.textScale,
            )
        }
    }
}

val LocalMotif = staticCompositionLocalOf { MotifPalette.from(Appearance()) }

/** Shorthand for the active palette inside composables. */
val motif: MotifPalette
    @Composable get() = LocalMotif.current

@Composable
fun MotifTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val p = MotifPalette.from(appearance)
    val scheme = darkColorScheme(
        primary = p.accent,
        onPrimary = p.ground,
        secondary = p.text,
        onSecondary = p.ground,
        background = p.ground,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.card,
        onSurfaceVariant = p.textMuted,
        surfaceContainer = p.card,
        surfaceContainerHigh = p.card,
        surfaceContainerLow = p.surface,
        outline = p.line,
        outlineVariant = p.line,
    )
    val s = p.textScale
    val type = Typography(
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = (26 * s).sp, letterSpacing = (-0.3).sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = (22 * s).sp, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = (15 * s).sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = (15 * s).sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = (14 * s).sp),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = (12 * s).sp),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = (11 * s).sp, letterSpacing = 1.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = (12 * s).sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = (14 * s).sp),
    )
    CompositionLocalProvider(LocalMotif provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
