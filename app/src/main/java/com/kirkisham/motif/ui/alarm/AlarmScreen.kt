package com.kirkisham.motif.ui.alarm

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.alarm.Alarm
import com.kirkisham.motif.alarm.AlarmSong
import com.kirkisham.motif.alarm.OrderMode
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.MotifTextField
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
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

/**
 * Alarm editor (board Alarm, spec §3.8). Since 0.12 it is the edit screen reached from the Alarms
 * list: [alarmId] null means a new alarm; Save writes, schedules and returns to the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmScreen(app: MotifApp, alarmId: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by remember { mutableStateOf(Alarm.blank(UUID.randomUUID().toString())) }
    val editingExisting = alarmId != null
    var loaded by remember { mutableStateOf(alarmId == null) }
    // Time entry (0.11): an inline keypad under the big time replaces the Material dial sheet.
    // Open from the start for a new alarm; Edit opens it on tap. [digits] is the dialer-style entry.
    var keypadOpen by remember { mutableStateOf(alarmId == null) }
    var digits by remember { mutableStateOf("") }
    var pickDate by remember { mutableStateOf(false) }
    var pickSongs by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(alarmId) {
        if (alarmId != null) {
            val a = app.alarmStore.get(alarmId)
            if (a == null) { onBack(); return@LaunchedEffect }
            draft = a; loaded = true
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(3_000); now = System.currentTimeMillis() } }
    // Notifications (0.12): nothing rings visibly on Android 13+ without them, so Save asks if still missing.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun save() {
        if (draft.name.isBlank()) { line = "Give the alarm a name first."; return }
        if (!draft.oneTime && draft.days.isEmpty()) { line = "Pick at least one day."; return }
        var a = draft.copy(enabled = true, fadeSeconds = draft.fadeSeconds.coerceIn(0, ((draft.songs.firstOrNull()?.durationMs ?: 0L) / 1000).toInt()))
        // A one-time alarm whose date is already past rolls to its next occurrence; keep the stored date honest.
        if (a.oneTime) a.nextTrigger()?.let { a = a.copy(date = it.toLocalDate().toString()) }
        val saved = a
        scope.launch {
            app.alarmStore.save(saved)
            app.alarmScheduler.schedule(saved)
            if (AlarmPermissionStatus.needsNotificationRequest(context)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            onBack()
        }
    }

    /** Dialer entry → clamped time written straight into the draft so "Rings in" and Save see it. */
    fun applyDigits(d: String) {
        digits = d
        if (d.isEmpty()) return
        val h12 = d.dropLast(2).toIntOrNull() ?: 0
        val m = (d.takeLast(2).toIntOrNull() ?: 0).coerceAtMost(59)
        val hour12 = h12.coerceAtMost(12).let { if (it == 0) 12 else it }
        val pm = draft.hour >= 12
        val hour = (hour12 % 12) + (if (pm) 12 else 0)
        draft = draft.copy(hour = hour, minute = m)
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

    if (!loaded) return
    val firstLen = ((draft.songs.firstOrNull()?.durationMs ?: 0L) / 1000).toInt()
    val next = remember(draft, now) { draft.copy(enabled = true).nextTrigger() }

    // A press anywhere outside the time block closes the keypad (watched on the Initial pass, never consumed,
    // so the press still scrolls or taps whatever it landed on).
    var keypadBounds by remember { mutableStateOf<Rect?>(null) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { rootCoords = it }.pointerInput(keypadOpen) {
            if (!keypadOpen) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val root = rootCoords?.localToRoot(down.position) ?: return@awaitEachGesture
                if (keypadBounds?.contains(root) != true) { keypadOpen = false; digits = "" }
            }
        },
    ) {
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
        line?.let { l -> item { PlainLine(l, Modifier.padding(start = 8.dp)) } }

        // Time (0.11): the big time is the control; tapping it opens the keypad below.
        item {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp).onGloballyPositioned { keypadBounds = it.boundsInRoot() },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.clickable { if (!keypadOpen) { digits = ""; keypadOpen = true } }, verticalAlignment = Alignment.Bottom) {
                    val h12 = if (draft.hour % 12 == 0) 12 else draft.hour % 12
                    // While typing, show the entry as a dialer would: "–:–6", "–:63", "6:30", "12:15".
                    val shown = if (keypadOpen && digits.isNotEmpty()) {
                        val h = digits.dropLast(2).ifEmpty { "\u2013" }
                        val m = digits.takeLast(2).padStart(2, '\u2013')
                        "$h:$m"
                    } else "%d:%02d".format(h12, draft.minute)
                    Text(shown, fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, color = motif.text, lineHeight = 64.sp)
                    Text(if (draft.hour < 12) "AM" else "PM", fontSize = 22.sp, color = motif.textMuted, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                }
                Text(ringsIn(next, now), style = MaterialTheme.typography.bodySmall, color = motif.textMuted)
                if (keypadOpen) {
                    TimeKeypad(
                        pm = draft.hour >= 12,
                        onDigit = { d -> applyDigits((digits + d).takeLast(4)) },
                        onBackspace = { applyDigits(digits.dropLast(1)) },
                        onPeriod = { pm -> draft = draft.copy(hour = (draft.hour % 12) + (if (pm) 12 else 0)) },
                        onDone = { keypadOpen = false; digits = "" },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
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
                    // No date picked: show the day it will actually ring (the next occurrence of the time).
                    val d = next?.toLocalDate() ?: draft.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
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

    }
    }

    if (pickDate) DateSheet(draft.date ?: next?.toLocalDate()?.toString(), onPick = { draft = draft.copy(date = it.toString()) }, onDismiss = { pickDate = false })
    if (pickSongs) SongPickerSheet(app, draft.songs, onAdd = { draft = draft.copy(songs = draft.songs + it) }, onDismiss = { pickSongs = false })
    if (customMinutes) MinutesSheet(draft.playForMinutes, onPick = { draft = draft.copy(playForMinutes = it) }, onDismiss = { customMinutes = false })
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

/**
 * Inline time keypad (0.11): digits 1–9, then AM/PM, 0 and backspace, 56 dp hairline keys in the
 * Motif style, plus a small Done. Entry fills right to left like a phone dialer; the caller clamps.
 */
@Composable
private fun TimeKeypad(
    pm: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPeriod: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    @Composable
    fun Key(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            modifier.height(56.dp).clip(shape).border(1.dp, motif.chipLine, shape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
    @Composable
    fun Digit(c: Char, modifier: Modifier) = Key(modifier, { onDigit(c) }) {
        Text(c.toString(), fontSize = 22.sp, fontWeight = FontWeight.Light, color = motif.text)
    }
    Column(modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> Digit(c, Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(Modifier.weight(1f), { onPeriod(!pm) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AM", fontSize = 14.sp, fontWeight = if (!pm) FontWeight.Medium else FontWeight.Light, color = if (!pm) motif.text else motif.textDim)
                    Text("PM", fontSize = 14.sp, fontWeight = if (pm) FontWeight.Medium else FontWeight.Light, color = if (pm) motif.text else motif.textDim)
                }
            }
            Digit('0', Modifier.weight(1f))
            Key(Modifier.weight(1f), onBackspace) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace", tint = motif.textMuted, modifier = Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "Done", style = MaterialTheme.typography.labelLarge, color = motif.accent, textAlign = TextAlign.Center,
                modifier = Modifier.clip(shape).clickable(onClick = onDone).padding(horizontal = 16.dp, vertical = 12.dp),
            )
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
