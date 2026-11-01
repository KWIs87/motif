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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch

/** Settings (board Settings), Build 1 subset: Appearance plus the "Widget uses these settings" switch. */
@Composable
fun SettingsScreen(app: MotifApp, onBack: () -> Unit) {
    val appearance by app.prefs.appearance.collectAsStateWithLifecycle(Appearance())
    val widgetUsesApp by app.prefs.widgetUsesApp.collectAsStateWithLifecycle(true)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<String?>(null) } // "text", "bg", "glow"

    fun save(a: Appearance) = scope.launch { app.prefs.setAppearance(a) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 24.dp, bottom = 40.dp),
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

        Column(Modifier.padding(start = 8.dp)) {
            SectionLabel("Widget", Modifier.padding(bottom = 8.dp))
            SettingRow("Widget uses these settings") {
                Switch(
                    checked = widgetUsesApp,
                    onCheckedChange = { on -> scope.launch { app.prefs.setWidgetUsesApp(on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = motif.accent, checkedThumbColor = motif.ground, uncheckedTrackColor = motif.card, uncheckedThumbColor = motif.textMuted, uncheckedBorderColor = motif.chipLine),
                )
            }
            Text(
                "Off: the widget keeps its own colors, size and glow, set from the widget itself. Background opacity is set on the widget only.",
                style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(top = 10.dp), lineHeight = 18.sp,
            )
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
