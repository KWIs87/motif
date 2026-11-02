package com.kirkisham.motif.ui.alarm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.alarm.AlarmLog
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.motif
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val STAMP = DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")

/** Alarm log (0.12): "time · event · detail", newest first, with Clear. Read-only otherwise. */
@Composable
fun AlarmLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AlarmLog.load(context) }
    val entries by AlarmLog.entries.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = MINI_PLAYER_SPACE),
    ) {
        item {
            TopBar("Alarm log", onBack) {
                if (entries.isNotEmpty()) Text(
                    "Clear", style = MaterialTheme.typography.labelLarge, color = motif.textMuted,
                    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { AlarmLog.clear(context) }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        if (entries.isEmpty()) item { PlainLine("Nothing logged yet. Scheduling or testing an alarm writes here.", Modifier.padding(start = 8.dp)) }
        items(entries, key = { "${it.at}-${it.event}-${it.detail.hashCode()}" }) { e ->
            Column(Modifier.padding(start = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Instant.ofEpochMilli(e.at).atZone(zone).format(STAMP) + " · " + e.event,
                            style = MaterialTheme.typography.bodyMedium, color = if (e.event == "error") motif.accent else motif.text,
                        )
                        if (e.detail.isNotBlank()) Text(e.detail, fontSize = 12.sp, lineHeight = 16.sp, color = motif.textMuted)
                    }
                }
                Hairline()
            }
        }
    }
}
