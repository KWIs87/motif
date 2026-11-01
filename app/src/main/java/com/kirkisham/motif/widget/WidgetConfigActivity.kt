package com.kirkisham.motif.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.Appearance
import com.kirkisham.motif.data.WidgetStyle
import com.kirkisham.motif.ui.MotifTheme
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.motif
import com.kirkisham.motif.ui.settings.ColorSheet
import com.kirkisham.motif.ui.settings.MotifSlider
import com.kirkisham.motif.ui.settings.SettingRow
import com.kirkisham.motif.ui.settings.Swatch
import kotlinx.coroutines.launch

/**
 * Widget settings (board Widget, "Tweaks"): the switch to follow the app's Appearance, then the
 * widget's own text color, background color, background opacity, text size, glow color and intensity.
 * Opened when the widget is added, from the launcher's reconfigure action, or by tapping the widget's art.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // A configure activity must return RESULT_OK with the id, or the launcher drops the widget.
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val app = MotifApp.get(this)
        setContent {
            val appearance by app.prefs.appearance.collectAsStateWithLifecycle(Appearance())
            MotifTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetConfigScreen(app, onDone = { MotifWidget.update(this); finish() })
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        MotifWidget.update(this)
    }
}

@Composable
private fun WidgetConfigScreen(app: MotifApp, onDone: () -> Unit) {
    val style by app.prefs.widgetStyle.collectAsStateWithLifecycle(WidgetStyle())
    val usesApp by app.prefs.widgetUsesApp.collectAsStateWithLifecycle(true)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<String?>(null) }
    fun save(w: WidgetStyle) = scope.launch { app.prefs.setWidgetStyle(w) }
    val own = !usesApp
    val dim = if (own) 1f else 0.4f

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopBar("Widget settings", onDone)
        Column(Modifier.padding(start = 8.dp)) {
            SettingRow("Widget uses app settings") {
                Switch(
                    checked = usesApp,
                    onCheckedChange = { on -> scope.launch { app.prefs.setWidgetUsesApp(on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = motif.accent, checkedThumbColor = motif.ground, uncheckedTrackColor = motif.card, uncheckedThumbColor = motif.textMuted, uncheckedBorderColor = motif.chipLine),
                )
            }
            Text(
                if (usesApp) "The widget follows the Appearance settings in the app. Background opacity below still applies."
                else "The widget keeps the colors, size and glow set here.",
                style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(top = 10.dp), lineHeight = 18.sp,
            )
        }
        Column(Modifier.padding(start = 8.dp)) {
            SectionLabel("Widget", Modifier.padding(bottom = 8.dp))
            Column(Modifier.alphaCompat(dim)) {
                SettingRow("Text color") { Swatch(Color(style.textColor)) { if (own) picking = "text" } }
                SettingRow("Background color") { Swatch(Color(style.backgroundColor)) { if (own) picking = "bg" } }
            }
            Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Background opacity", style = MaterialTheme.typography.bodyLarge, color = motif.text)
                    Text("${style.backgroundOpacity}%", style = MaterialTheme.typography.bodyLarge, color = motif.textMuted)
                }
                MotifSlider(style.backgroundOpacity / 100f) { save(style.copy(backgroundOpacity = (it * 100).toInt())) }
            }
            Hairline()
            Column(Modifier.alphaCompat(dim)) {
                SettingRow("Text size") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("S" to "Small", "M" to "Medium", "L" to "Large").forEach { (k, label) ->
                            Chip(label, selected = style.textSize == k, enabled = own, onClick = { save(style.copy(textSize = k)) })
                        }
                    }
                }
                SettingRow("Border glow color") { Swatch(Color(style.glowColor)) { if (own) picking = "glow" } }
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Glow intensity", style = MaterialTheme.typography.bodyLarge, color = motif.text)
                        Text("${style.glowIntensity}%", style = MaterialTheme.typography.bodyLarge, color = motif.textMuted)
                    }
                    MotifSlider(style.glowIntensity / 100f) { if (own) save(style.copy(glowIntensity = (it * 100).toInt())) }
                }
                Hairline()
            }
        }
    }

    picking?.let { which ->
        val current = when (which) { "text" -> style.textColor; "bg" -> style.backgroundColor; else -> style.glowColor }
        ColorSheet(
            title = when (which) { "text" -> "Text color"; "bg" -> "Background color"; else -> "Border glow color" },
            current = Color(current),
            onPick = { c ->
                val argb = c.toArgb()
                save(when (which) { "text" -> style.copy(textColor = argb); "bg" -> style.copy(backgroundColor = argb); else -> style.copy(glowColor = argb) })
            },
            onDismiss = { picking = null },
        )
    }
}

private fun Modifier.alphaCompat(a: Float): Modifier = if (a >= 1f) this else this.alpha(a)
