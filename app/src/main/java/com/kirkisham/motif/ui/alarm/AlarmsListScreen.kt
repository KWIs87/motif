package com.kirkisham.motif.ui.alarm

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.alarm.Alarm
import com.kirkisham.motif.alarm.AlarmLog
import com.kirkisham.motif.alarm.ringsInText
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.combinedClickableCompat
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** The small green dot for a permission that is in place. The palette has no green, so this one is fixed. */
private val OK_GREEN = Color(0xFF5BA55B)

/**
 * Alarms list (0.12, spec §3.8): one row per saved alarm with its switch, "Rings in" under the next
 * one to ring, the permission block when anything is missing, then the Test alarm and Alarm log rows.
 * Tap a row to edit, long press to remove (Undo pill). The plus and the empty-state pill open the editor.
 */
@Composable
fun AlarmsListScreen(app: MotifApp, onBack: () -> Unit, onNew: () -> Unit, onEdit: (String) -> Unit, onLog: () -> Unit) {
    val all by app.alarmStore.alarms.collectAsStateWithLifecycle(emptyList())
    val alarms = remember(all) { all.filterNot { it.transient } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var perms by remember { mutableStateOf(AlarmPermissionStatus.read(context)) }
    var line by remember { mutableStateOf<String?>(null) }
    var testAt by remember { mutableLongStateOf(0L) }

    // Re-check permissions and the clock on resume and every 3 s while visible.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                perms = AlarmPermissionStatus.read(context)
                delay(if (testAt > now) 1_000 else 3_000)
            }
        }
    }
    // Test alarm countdown line runs each second while one is pending.
    LaunchedEffect(testAt) { while (testAt > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(500) } }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        perms = AlarmPermissionStatus.read(context)
        // Denied twice: Android will not show the prompt again, so open the app's notification settings.
        if (!granted) AlarmPermissionStatus.openNotificationSettings(context)
    }
    val askNotifications = {
        if (AlarmPermissionStatus.needsNotificationRequest(context)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else AlarmPermissionStatus.openNotificationSettings(context)
    }
    // First visit on Android 13+: ask straight away, since nothing rings without it.
    LaunchedEffect(Unit) { if (AlarmPermissionStatus.needsNotificationRequest(context)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val zNow = remember(now) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()) }
    val nextId = remember(alarms, zNow) { alarms.mapNotNull { a -> a.nextTrigger(zNow)?.let { a.id to it } }.minByOrNull { it.second }?.first }

    fun remove(a: Alarm) {
        scope.launch {
            app.alarmScheduler.cancel(a.id); app.alarmStore.delete(a.id)
            app.undo.post("Removed", undo = { app.alarmStore.save(a); if (a.enabled) app.alarmScheduler.schedule(a) })
        }
    }

    fun testAlarm() {
        scope.launch {
            val at = System.currentTimeMillis() + TEST_SECONDS * 1000L
            val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            val first = all.firstOrNull { !it.transient }
            val test = Alarm(
                id = "test-" + UUID.randomUUID().toString(), name = "Test", hour = t.hour, minute = t.minute,
                oneTime = true, date = t.toLocalDate().toString(),
                songs = first?.songs.orEmpty(), order = first?.order ?: Alarm.blank("x").order,
                fadeSeconds = first?.fadeSeconds ?: 0, startVolume = first?.startVolume ?: 10, endVolume = first?.endVolume ?: 65,
                playForMinutes = 3, snoozeMinutes = 5, enabled = true, transient = true,
            )
            runCatching { app.alarmScheduler.scheduleAt(test, at) }
                .onSuccess { testAt = at; line = null }
                .onFailure { AlarmLog.error(context, "test alarm", it); line = "Could not schedule the test alarm. See the log." }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = MINI_PLAYER_SPACE),
    ) {
        item {
            TopBar("Alarms", onBack) {
                if (alarms.isNotEmpty()) IconButton44(Icons.Outlined.Add, "Add alarm", onNew)
            }
        }
        line?.let { l -> item { PlainLine(l, Modifier.padding(start = 8.dp)) } }

        if (alarms.isEmpty()) item {
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentAlignment = Alignment.Center) {
                PillButton("Add alarm", onClick = onNew)
            }
        }
        items(alarms, key = { it.id }) { a ->
            AlarmRow(
                a,
                ringsIn = if (a.id == nextId) ringsInText(a.nextTrigger(zNow), zNow) else null,
                onClick = { onEdit(a.id) },
                onLongClick = { remove(a) },
                onToggle = { on -> scope.launch { app.alarmStore.setEnabled(a.id, on); app.alarmScheduler.schedule(a.copy(enabled = on)) } },
            )
        }

        if (!perms.allOk) item {
            Column(Modifier.padding(start = 8.dp, top = 28.dp)) {
                SectionLabel("Permissions", Modifier.padding(bottom = 6.dp))
                PermissionRow("Notifications", perms.notifications, askNotifications)
                PermissionRow("Exact alarms", perms.exactAlarms) { AlarmPermissionStatus.openExactAlarmSettings(context) }
                PermissionRow("Show over lock screen", perms.fullScreen) { AlarmPermissionStatus.openFullScreenSettings(context) }
                PermissionRow("Battery exemption", perms.battery) { AlarmPermissionStatus.requestBatteryExemption(context) }
            }
        }

        item {
            Column(Modifier.padding(start = 8.dp, top = 28.dp)) {
                Hairline()
                TextRow(if (testAt > now) "Test alarm in ${((testAt - now + 999) / 1000).coerceAtLeast(0)} s" else "Test alarm", enabled = testAt <= now) { testAlarm() }
                Hairline()
                TextRow("Alarm log", onClick = onLog)
                Hairline()
                Text(
                    "Test alarm rings in $TEST_SECONDS s through the same path as a real one, with the first alarm's songs and fade (or the tone). Long press an alarm to remove it.",
                    style = MaterialTheme.typography.bodySmall, color = motif.textDim, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

private const val TEST_SECONDS = 15

@Composable
private fun AlarmRow(a: Alarm, ringsIn: String?, onClick: () -> Unit, onLongClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Column(Modifier.padding(start = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).combinedClickableCompat(true, onClick, onLongClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val h12 = if (a.hour % 12 == 0) 12 else a.hour % 12
                    Text("%d:%02d".format(h12, a.minute), fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, lineHeight = 36.sp, color = if (a.enabled) motif.text else motif.textMuted)
                    Text(if (a.hour < 12) "AM" else "PM", fontSize = 14.sp, color = motif.textMuted, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
                }
                Text(
                    listOf(a.name.ifBlank { "Alarm" }, a.repeatSummary()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (ringsIn != null) Text(ringsIn, style = MaterialTheme.typography.bodySmall, color = motif.accent)
            }
            Box(Modifier.size(width = 60.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                Switch(
                    checked = a.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = motif.accent, checkedThumbColor = motif.ground, uncheckedTrackColor = motif.card, uncheckedThumbColor = motif.textMuted, uncheckedBorderColor = motif.chipLine),
                )
            }
        }
        Hairline()
    }
}

/** Hairline row: label, then a green dot when granted or a muted "Allow" text button when not. */
@Composable
private fun PermissionRow(label: String, ok: Boolean, onAllow: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = motif.text, modifier = Modifier.weight(1f))
            if (ok) Box(Modifier.padding(end = 18.dp).size(8.dp).clip(CircleShape).background(OK_GREEN))
            else Text(
                "Allow", style = MaterialTheme.typography.labelLarge, color = motif.textMuted,
                modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAllow).padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        Hairline()
    }
}

/** Muted text button as a full-width 48 dp row. */
@Composable
private fun TextRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) motif.textMuted else motif.textDim)
        Spacer(Modifier.weight(1f))
    }
}
