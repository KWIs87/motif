package com.kirkisham.motif.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Motif palette, matching the wireframes: near-black ground, warm off-white text, one gold accent. */
object MotifColors {
    val Ground = Color(0xFF121212)
    val Surface = Color(0xFF1A1A1A)
    val Line = Color(0xFF1E1E1E)
    val Text = Color(0xFFE8E6E1)
    val TextMuted = Color(0xFF8A8882)
    val Accent = Color(0xFFC9A96E)
}

private val scheme = darkColorScheme(
    primary = MotifColors.Accent,
    onPrimary = MotifColors.Ground,
    background = MotifColors.Ground,
    onBackground = MotifColors.Text,
    surface = MotifColors.Surface,
    onSurface = MotifColors.Text,
    onSurfaceVariant = MotifColors.TextMuted,
    outline = MotifColors.Line,
)

@Composable
fun MotifTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
