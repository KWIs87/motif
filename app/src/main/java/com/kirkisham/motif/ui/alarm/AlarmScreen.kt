package com.kirkisham.motif.ui.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.alarm.Alarm
import com.kirkisham.motif.alarm.AlarmSong
import com.kirkisham.motif.alarm.OrderMode
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.ConfirmSheet
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.MotifTextField
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.components.combinedClickableCompat
import com.kirkisham.motif.ui.components.formatClock
import com.kirkisham.motif.ui.motif
import com.kirkisham.motif.ui.settings.MotifSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private val PLAY_FOR = listOf(5, 10, 20)
private val SNOOZE = listOf(5, 10, 15)

/** Alarm editor plus the saved list (board Alarm, spec §3.8). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmScreen(app: MotifApp, onBack: () -> Unit) {
    val saved by app.alarmStore.alarms.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by remember { mutableStateOf(Alarm.blank(UUID.randomUUID().toString())) }
    var editingExisting by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickSongs by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Alarm?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    var exactOk by remember { mutableStateOf(app.alarmScheduler.canScheduleExact()) }
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    var fullScreenOk by remember { mutableStateOf(Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) }
    // Re-check permissions every few seconds so the lines disappear after the user grants them in Settings.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            now = System.currentTimeMillis()
            exactOk = app.alarmScheduler.canScheduleExact()
            batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
            fullScreenOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        }
    }

    fun save() {
        if (draft.name.isBlank()) { line = "Give the alarm a name first."; return }
        if (!draft.oneTime && draft.days.isEmpty()) { line = "Pick at least one day."; return }
        val a = draft.copy(enabled = true, fadeSeconds = draft.fadeSeconds.coerceIn(0, ((draft.songs.firstOrNull()?.durationMs ?: 0L) / 1000).toInt()))
        scope.launch {
            app.alarmStore.save(a)
            app.alarmScheduler.schedule(a)
            line = if (a.songs.isEmpty()) "Saved. No songs yet, so it will ring with the built-in tone." else "Saved."
            draft = Alarm.blank(UUID.randomUUID().toString())
            editingExisting = false
        }
    }

    // Song drag to reorder (same technique as the Playlist screen).
    val density = LocalDensity.current
    val rowPx = with(density) { 60.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragShift by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    fun finishDrag() {
        val from = dragIndex; val to = (dragIndex + dragShift).coerceIn(0, draft.songs.lastIndex.coerceAtLeast(0))
        dragIndex = -1; dragShift = 0; dragOffset = 0f
        if (from < 0 || from == to || draft.songs.isEmpty()) return
        val l = draft.songs.toMutableList(); val it = l.removeAt(from); l.add(to, it)
        draft = draft.copy(songs = l)
    }

    val firstLen = ((draft.songs.firstOrNull()?.durationMs ?: 0L) / 1000).toInt()
    val next = remember(draft, now) { draft.copy(enabled = true).nextTrigger() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = MINI_PLAYER_SPACE),
        userScrollEnabled = dragIndex < 0,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            TopBar(if (editingExisting) "Edit alarm" else "New alarm", onBack) {
                PillButton("Save", onClick = { save() }, modifier = Modifier.height(36.dp))
            }
        }
        if (!exactOk || !batteryOk || !fullScreenOk) item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!exactOk) Text(
                    "Android needs permission for exact alarms so this rings on time. Tap to allow.",
                    style = MaterialTheme.typography.bodySmall, color = motif.accent,
                    modifier = Modifier.clickable {
                        if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    },
                )
                if (!fullScreenOk) Text(
                    "Android needs permission to show the alarm over the lock screen. Tap to allow.",
                    style = MaterialTheme.typography.bodySmall, color = motif.accent,
                    modifier = Modifier.clickable {
                        if (Build.VERSION.SDK_INT >= 34) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                    },
                )
                if (!batteryOk) Text(
                    "Battery optimization can delay alarms. Tap to exempt Motif.",
                    style = MaterialTheme.typography.bodySmall, color = motif.accent,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
        }
        line?.let { l -> item { PlainLine(l, Modifier.padding(start = 8.dp)) } }

        // Time
        item {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.clickable { pickTime = true }, verticalAlignment = Alignment.Bottom) {
                    val h12 = if (draft.hour % 12 == 0) 12 else draft.hour % 12
                    Text("%d:%02d".format(h12, draft.minute), fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, color = motif.text, lineHeight = 64.sp)
                    Text(if (draft.hour < 12) "AM" else "PM", fontSize = 22.sp, color = motif.textMuted, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                }
                Text(ringsIn(next, now), style = MaterialTheme.typography.bodySmall, color = motif.textMuted)
            }
        }

        // Name
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Name")
                MotifTextField(draft.name, { draft = draft.copy(name = it) }, placeholder = "Name this alarm")
            }
        }

        // Repeat
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Repeat")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("One time", draft.oneTime, { draft = draft.copy(oneTime = true) }, height = 32.dp)
                    Chip("Recurring", !draft.oneTime, { draft = draft.copy(oneTime = false) }, height = 32.dp)
                }
                if (draft.oneTime) {
                    val d = draft.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().plusDays(1)
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, motif.chipLine, RoundedCornerShape(10.dp)).clickable { pickDate = true }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text(d.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")), style = MaterialTheme.typography.bodyLarge, color = motif.text) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, label) ->
                            val on = d in draft.days
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (on) motif.accent else androidx.compose.ui.graphics.Color.Transparent)
                                    .border(1.dp, if (on) motif.accent else motif.chipLine, CircleShape)
                                    .clickable { draft = draft.copy(days = if (on) draft.days - d else draft.days + d) },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) motif.ground else motif.textMuted) }
                        }
                    }
                }
            }
        }

        // Songs
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Songs · ${draft.songs.size} · ${formatClock(draft.totalMs)}", Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).border(1.dp, motif.accent, CircleShape).clickable { pickSongs = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Add, "Add song", tint = motif.accent, modifier = Modifier.size(18.dp)) }
            }
        }
        itemsIndexed(draft.songs, key = { i, s -> "${s.uri}-$i" }) { i, s ->
            val isDragging = dragIndex == i
            val shiftPx = when {
                dragIndex < 0 || isDragging -> 0f
                dragShift > 0 && i > dragIndex && i <= dragIndex + dragShift -> -rowPx
                dragShift < 0 && i < dragIndex && i >= dragIndex + dragShift -> rowPx
                else -> 0f
            }
            SongRow(
                s,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else shiftPx }
                    .then(if (isDragging) Modifier.background(motif.surface) else Modifier)
                    .pointerInput(i, draft.songs.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragIndex = i; dragShift = 0; dragOffset = 0f },
                            onDrag = { change, delta ->
                                change.consume()
                                dragOffset += delta.y
                                dragShift = (dragOffset / rowPx).roundToInt().coerceIn(-i, draft.songs.lastIndex - i)
                            },
                            onDragEnd = { finishDrag() },
                            onDragCancel = { finishDrag() },
                        )
                    },
                onRemove = {
                    val before = draft.songs
                    draft = draft.copy(songs = draft.songs.filterIndexed { j, _ -> j != i })
                    app.undo.post(undo = { draft = draft.copy(songs = before) })
                },
            )
        }
        item {
            Text(
                "Plus adds from search or any of your playlists. Long press and drag a song to reorder.",
                style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(start = 8.dp), lineHeight = 18.sp,
            )
        }

        // Order
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Order")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Start to end", draft.order == OrderMode.START_TO_END, { draft = draft.copy(order = OrderMode.START_TO_END) }, height = 32.dp)
                    Chip("Repeat first song", draft.order == OrderMode.REPEAT_FIRST, { draft = draft.copy(order = OrderMode.REPEAT_FIRST) }, height = 32.dp)
                    Chip("Random start", draft.order == OrderMode.RANDOM_START, { draft = draft.copy(order = OrderMode.RANDOM_START) }, height = 32.dp)
                }
            }
        }

        // Volume
        item {
            Column(Modifier.padding(start = 8.dp)) {
                SectionLabel("Volume", Modifier.padding(bottom = 8.dp))
                SliderRow("Fade in", "${formatClock(draft.fadeSeconds.coerceAtMost(firstLen) * 1000L)} of ${formatClock(firstLen * 1000L)}",
                    valueF = if (firstLen == 0) 0f else draft.fadeSeconds.coerceAtMost(firstLen) / firstLen.toFloat(),
                    enabled = firstLen > 0,
                    onChange = { draft = draft.copy(fadeSeconds = (it * firstLen).roundToInt()) },
                    footer = "0:00" to "length of the first song")
                SliderRow("Start volume", "${draft.startVolume}%", draft.startVolume / 100f, onChange = { draft = draft.copy(startVolume = (it * 100).roundToInt()) })
                SliderRow("End volume", "${draft.endVolume}%", draft.endVolume / 100f, onChange = { draft = draft.copy(endVolume = (it * 100).roundToInt()) })
            }
        }

        // Play for
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Play for")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PLAY_FOR.forEach { m -> Chip("$m min", draft.playForMinutes == m, { draft = draft.copy(playForMinutes = m) }, height = 32.dp, onLongClick = { customMinutes = true }) }
                    val custom = draft.playForMinutes?.takeIf { it !in PLAY_FOR }
                    if (custom != null) Chip("$custom min", true, { customMinutes = true }, height = 32.dp, onLongClick = { customMinutes = true })
                    Chip("Until dismissed", draft.playForMinutes == null, { draft = draft.copy(playForMinutes = null) }, height = 32.dp)
                }
                Text("Default 10 min, then silence unless you dismiss first. Long press a chip to type minutes. The list loops if it is shorter than the time.",
                    style = MaterialTheme.typography.bodySmall, color = motif.textMuted, lineHeight = 18.sp)
            }
        }

        // Snooze
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Snooze")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SNOOZE.forEach { m -> Chip("$m min", draft.snoozeMinutes == m, { draft = draft.copy(snoozeMinutes = m) }, height = 32.dp) }
                }
                Text("Either volume button snoozes while the alarm is ringing. Dismiss is on screen only.", style = MaterialTheme.typography.bodySmall, color = motif.textMuted, lineHeight = 18.sp)
            }
        }

        // Saved alarms
        item {
            Column(Modifier.padding(start = 8.dp)) {
                SectionLabel("Saved alarms", Modifier.padding(bottom = 8.dp))
                if (saved.isEmpty()) PlainLine("No alarms yet.")
                saved.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .combinedClickableCompat(true, { draft = a; editingExisting = true; line = null }, { deleting = a }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.name.ifBlank { "Alarm" }, style = MaterialTheme.typography.titleMedium, color = if (a.enabled) motif.text else motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.summary(), style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(
                            checked = a.enabled,
                            onCheckedChange = { on -> scope.launch { app.alarmStore.setEnabled(a.id, on); app.alarmScheduler.schedule(a.copy(enabled = on)) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = motif.accent, checkedThumbColor = motif.ground, uncheckedTrackColor = motif.card, uncheckedThumbColor = motif.textMuted, uncheckedBorderColor = motif.chipLine),
                        )
                    }
                    Hairline()
                }
                Text("Tap a saved alarm to load it into the form above; long press to delete.", style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = Modifier.padding(top = 8.dp), lineHeight = 18.sp)
            }
        }
    }

    if (pickTime) TimeSheet(draft.hour, draft.minute, onPick = { h, m -> draft = draft.copy(hour = h, minute = m) }, onDismiss = { pickTime = false })
    if (pickDate) DateSheet(draft.date, onPick = { draft = draft.copy(date = it.toString()) }, onDismiss = { pickDate = false })
    if (pickSongs) SongPickerSheet(app, draft.songs, onAdd = { draft = draft.copy(songs = draft.songs + it) }, onDismiss = { pickSongs = false })
    if (customMinutes) MinutesSheet(draft.playForMinutes, onPick = { draft = draft.copy(playForMinutes = it) }, onDismiss = { customMinutes = false })
    deleting?.let { a ->
        ConfirmSheet("Delete “${a.name.ifBlank { "Alarm" }}”?", "Delete",
            onConfirm = {
                scope.launch {
                    app.alarmScheduler.cancel(a.id); app.alarmStore.delete(a.id)
                    if (draft.id == a.id) { draft = Alarm.blank(UUID.randomUUID().toString()); editingExisting = false }
                    // Undo (0.10): the alarm object is in hand, so undo saves and schedules it again.
                    app.undo.post("Deleted", undo = { app.alarmStore.save(a); if (a.enabled) app.alarmScheduler.schedule(a) })
                }
            },
            onDismiss = { deleting = null })
    }
}

private fun ringsIn(next: ZonedDateTime?, nowMs: Long): String {
    if (next == null) return "Pick a day"
    val d = Duration.between(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()), next)
    val h = d.toHours(); val m = d.toMinutes() % 60
    return if (h >= 24) "Rings in ${h / 24} d ${h % 24} h" else if (h > 0) "Rings in $h h $m min" else "Rings in $m min"
}

@Composable
private fun SongRow(s: AlarmSong, modifier: Modifier, onRemove: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.DragHandle, "Drag to reorder", tint = motif.textDim, modifier = Modifier.size(20.dp))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(motif.card).border(1.dp, motif.line, RoundedCornerShape(6.dp))) {
                if (s.imageUrl != null) AsyncImage(s.imageUrl, null, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.artist} · ${formatClock(s.durationMs)}", style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton44(Icons.Outlined.Close, "Remove song", onRemove, tint = motif.textMuted, size = 18.dp)
        }
        Hairline()
    }
}

@Composable
private fun SliderRow(label: String, value: String, valueF: Float, enabled: Boolean = true, onChange: (Float) -> Unit, footer: Pair<String, String>? = null) {
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = motif.text)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = motif.textMuted)
        }
        if (enabled) MotifSlider(valueF, onChange = onChange)
        else Text("Add a song first", style = MaterialTheme.typography.bodySmall, color = motif.textDim)
        if (footer != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(footer.first, fontSize = 11.sp, color = motif.textDim); Text(footer.second, fontSize = 11.sp, color = motif.textDim)
        }
    }
    Hairline()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSheet(hour: Int, minute: Int, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = motif.card, contentColor = motif.text, dragHandle = null, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = motif.ground, clockDialSelectedContentColor = motif.ground, clockDialUnselectedContentColor = motif.text,
                    selectorColor = motif.accent, containerColor = motif.card,
                    periodSelectorBorderColor = motif.chipLine, periodSelectorSelectedContainerColor = motif.accent, periodSelectorSelectedContentColor = motif.ground,
                    periodSelectorUnselectedContentColor = motif.textMuted, periodSelectorUnselectedContainerColor = motif.card,
                    timeSelectorSelectedContainerColor = motif.text, timeSelectorSelectedContentColor = motif.ground,
                    timeSelectorUnselectedContainerColor = motif.ground, timeSelectorUnselectedContentColor = motif.text,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton("Set") { onPick(state.hour, state.minute); onDismiss() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSheet(current: String?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val initial = current?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().plusDays(1)
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = motif.card, contentColor = motif.text, dragHandle = null, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DatePicker(
                state = state, showModeToggle = false, title = null, headline = null,
                colors = DatePickerDefaults.colors(containerColor = motif.card, selectedDayContainerColor = motif.accent, selectedDayContentColor = motif.ground, todayDateBorderColor = motif.accent, todayContentColor = motif.text, dayContentColor = motif.text, weekdayContentColor = motif.textMuted, navigationContentColor = motif.text, yearContentColor = motif.text, currentYearContentColor = motif.accent, selectedYearContainerColor = motif.accent, selectedYearContentColor = motif.ground),
            )
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Set") { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()) }; onDismiss() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinutesSheet(current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current?.toString() ?: "") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = motif.card, contentColor = motif.text, dragHandle = null, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Play for how many minutes?", style = MaterialTheme.typography.bodyMedium, color = motif.textMuted)
            MotifTextField(text, { text = it.filter(Char::isDigit).take(3) }, placeholder = "Minutes", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                onDone = { text.toIntOrNull()?.takeIf { it > 0 }?.let(onPick); onDismiss() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Set", enabled = (text.toIntOrNull() ?: 0) > 0) { text.toIntOrNull()?.let(onPick); onDismiss() }
            }
        }
    }
}
