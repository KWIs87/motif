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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.Appearance
import com.kirkisham.motif.data.WidgetConfig
import com.kirkisham.motif.ui.MotifTheme
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.motif
import com.kirkisham.motif.ui.settings.SettingRow
import kotlinx.coroutines.launch

/**
 * Widget setup (0.12): shown when the widget is placed (`android:configure`), from the launcher's
 * widget settings (`reconfigurable`) and by tapping the art thumbnail. Options are per instance:
 * Show album line, Show times, Background transparency, Text size, Show art thumbnail at left.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // A configure activity must return RESULT_OK with the id, or the launcher drops the widget.
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val app = MotifApp.get(this)
        setContent {
            val appearance by app.prefs.appearance.collectAsStateWithLifecycle(Appearance())
            MotifTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetConfigScreen(app, widgetId, onDone = { redraw(); finish() })
                }
            }
        }
    }

    private fun redraw() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) MotifWidget.update(this, intArrayOf(widgetId)) else MotifWidget.update(this)
    }

    override fun onPause() {
        super.onPause()
        redraw()
    }
}

/** Label above a row of chips, hairline under (the chips do not fit beside the longer labels on a narrow phone). */
@Composable
private fun ChipRow(label: String, chips: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = motif.text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEachIndexed { i, (text, on) -> Chip(text, selected = on, onClick = { onPick(i) }) }
        }
    }
    Hairline()
}

@Composable
private fun WidgetConfigScreen(app: MotifApp, widgetId: Int, onDone: () -> Unit) {
    val cfg by app.prefs.widgetConfig(widgetId).collectAsStateWithLifecycle(WidgetConfig())
    val scope = rememberCoroutineScope()
    fun save(c: WidgetConfig) = scope.launch { app.prefs.setWidgetConfig(widgetId, c) }
    val switchColors = SwitchDefaults.colors(checkedTrackColor = motif.accent, checkedThumbColor = motif.ground, uncheckedTrackColor = motif.card, uncheckedThumbColor = motif.textMuted, uncheckedBorderColor = motif.chipLine)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopBar("Widget setup", onDone) { PillButton("Done", onClick = onDone, modifier = Modifier.padding(end = 0.dp)) }
        Column(Modifier.padding(start = 8.dp)) {
            SectionLabel("Lines", Modifier.padding(bottom = 8.dp))
            SettingRow("Show album line") { Switch(checked = cfg.showAlbum, onCheckedChange = { save(cfg.copy(showAlbum = it)) }, colors = switchColors) }
            SettingRow("Show times") { Switch(checked = cfg.showTimes, onCheckedChange = { save(cfg.copy(showTimes = it)) }, colors = switchColors) }
            SettingRow("Show art thumbnail at left") { Switch(checked = cfg.showArt, onCheckedChange = { save(cfg.copy(showArt = it)) }, colors = switchColors) }
            Text(
                "The second line reads \u201cArtist \u00B7 Album\u201d, or just the artist with the album line off. Times are elapsed and remaining either side of the progress bar.",
                style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(top = 10.dp), lineHeight = 18.sp,
            )
        }
        Column(Modifier.padding(start = 8.dp)) {
            SectionLabel("Look", Modifier.padding(bottom = 8.dp))
            ChipRow("Background transparency", listOf(0, 25, 50, 75).map { "$it%" to (cfg.transparency == it) }) { i -> save(cfg.copy(transparency = listOf(0, 25, 50, 75)[i])) }
            ChipRow("Text size", listOf("S", "M", "L").map { it to (cfg.textSize == it) }) { i -> save(cfg.copy(textSize = listOf("S", "M", "L")[i])) }
            Text(
                "The card is the app's ground color at the chosen transparency; text follows the app's text color and the progress bar uses the gold accent. Each widget keeps its own setup; long press the widget and choose Settings to come back here.",
                style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(top = 10.dp), lineHeight = 18.sp,
            )
        }
    }
}
