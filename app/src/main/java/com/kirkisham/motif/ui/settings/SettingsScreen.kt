package com.kirkisham.motif.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.Appearance
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch

/** Settings (board Settings), Build 1 subset: Appearance, then Updates. The widget's own options live on its setup screen (0.12). */
@Composable
fun SettingsScreen(app: MotifApp, onBack: () -> Unit) {
    val appearance by app.prefs.appearance.collectAsStateWithLifecycle(Appearance())
    val update by app.updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<String?>(null) } // "text", "bg", "glow"

    fun save(a: Appearance) = scope.launch { app.prefs.setAppearance(a) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 24.dp, bottom = MINI_PLAYER_SPACE),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopBar("Settings", onBack)

        Column(Modifier.padding(start = 8.dp)) {
            SectionLabel("Appearance", Modifier.padding(bottom = 8.dp))
            SettingRow("Text color") { Swatch(Color(appearance.textColor)) { picking = "text" } }
            SettingRow("Background color") { Swatch(Color(appearance.backgroundColor)) { picking = "bg" } }
            SettingRow("Text size") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("S" to "Small", "M" to "Medium", "L" to "Large").forEach { (k, label) ->
                        Chip(label, selected = appearance.textSize == k, onClick = { save(appearance.copy(textSize = k)) })
                    }
                }
            }
            SettingRow("Border glow color") { Swatch(Color(appearance.glowColor)) { picking = "glow" } }
            Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Glow intensity", style = MaterialTheme.typography.bodyLarge, color = motif.text)
                    Text("${appearance.glowIntensity}%", style = MaterialTheme.typography.bodyLarge, color = motif.textMuted)
                }
                MotifSlider(appearance.glowIntensity / 100f) { save(appearance.copy(glowIntensity = (it * 100).toInt())) }
            }
            Hairline()
        }

        // Updates (board Settings, bottom). The Improvement report section is parked and not shown.
        Column(Modifier.padding(start = 8.dp)) {
            SectionLabel("Updates", Modifier.padding(bottom = 8.dp))
            val u = update
            val title = when {
                u.available != null -> "Version ${u.available} available"
                u.checking -> "Checking for updates"
                else -> "Up to date"
            }
            val sub = buildString {
                append("Installed: ").append(u.installed)
                val extra = when {
                    u.available != null && u.notes != null -> u.notes
                    u.checkedAt > 0 -> "Checked " + ago(u.checkedAt)
                    else -> null
                }
                if (extra != null) append(" · ").append(extra)
            }
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable(enabled = !u.checking && !u.downloading) { scope.launch { app.updates.check() } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = motif.text)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (u.available != null) {
                    val shape = RoundedCornerShape(50)
                    val label = if (u.downloading) "Downloading" else "Install"
                    androidx.compose.foundation.layout.Box(
                        Modifier.height(36.dp).clip(shape).border(1.dp, motif.accent.copy(alpha = if (u.downloading) 0.5f else 1f), shape)
                            .clickable(enabled = !u.downloading) { app.updates.install() }.padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = motif.accent.copy(alpha = if (u.downloading) 0.5f else 1f))
                    }
                }
            }
            u.line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(top = 6.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    picking?.let { which ->
        val current = when (which) { "text" -> appearance.textColor; "bg" -> appearance.backgroundColor; else -> appearance.glowColor }
        ColorSheet(
            title = when (which) { "text" -> "Text color"; "bg" -> "Background color"; else -> "Border glow color" },
            current = Color(current),
            onPick = { c ->
                val argb = c.toArgb()
                save(when (which) { "text" -> appearance.copy(textColor = argb); "bg" -> appearance.copy(backgroundColor = argb); else -> appearance.copy(glowColor = argb) })
            },
            onDismiss = { picking = null },
        )
    }
}

/** "just now", "35 min ago", "3 hr ago", "2 days ago". */
private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms).coerceAtLeast(0)
    val min = d / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} hr ago"
        else -> "${min / (60 * 24)} days ago"
    }
}

@Composable
fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = motif.text)
            trailing()
        }
        Hairline()
    }
}

@Composable
fun Swatch(color: Color, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(28.dp).clip(CircleShape).background(color).border(1.dp, motif.chipLine, CircleShape).clickable(onClick = onClick),
    )
}

@Composable
fun MotifSlider(value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().height(28.dp),
        colors = SliderDefaults.colors(thumbColor = motif.accent, activeTrackColor = motif.accent, inactiveTrackColor = motif.card),
    )
}

/** Palette grid plus RGB sliders: enough to pick any color without a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorSheet(title: String, current: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableStateOf(current.red) }
    var g by remember { mutableStateOf(current.green) }
    var b by remember { mutableStateOf(current.blue) }
    val live = Color(r, g, b)
    val presets = listOf(
        0xFFE8E6E1, 0xFFFFFFFF, 0xFFC9A96E, 0xFF8A8882, 0xFF6EA7C9, 0xFF7FB38A, 0xFFC96E6E, 0xFFB08BD6,
        0xFF121212, 0xFF000000, 0xFF1A1A1A, 0xFF0E1A24, 0xFF1A1410, 0xFF0F1A12, 0xFF24130F, 0xFF2A2A2A,
    ).map { Color(it) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = motif.card, contentColor = motif.text, dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = motif.text)
                androidx.compose.foundation.layout.Box(Modifier.size(28.dp).clip(CircleShape).background(live).border(1.dp, motif.chipLine, CircleShape))
            }
            presets.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { c -> Swatch(c) { r = c.red; g = c.green; b = c.blue; onPick(c) } }
                }
            }
            listOf("R" to r, "G" to g, "B" to b).forEachIndexed { i, (label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.size(16.dp))
                    MotifSlider(v, Modifier.weight(1f)) { nv ->
                        when (i) { 0 -> r = nv; 1 -> g = nv; else -> b = nv }
                        onPick(Color(r, g, b))
                    }
                }
            }
        }
    }
}
