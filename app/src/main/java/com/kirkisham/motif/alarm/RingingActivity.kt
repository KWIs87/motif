package com.kirkisham.motif.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.Appearance
import com.kirkisham.motif.ui.MotifTheme
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.formatClock
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Ringing screen (board AlarmRinging): over the lock screen, screen stays on. Either volume key
 * snoozes (the event is consumed, volume does not change). Dismiss is on screen only.
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = MotifApp.get(this)
        AlarmLog.log(this, "ringing shown", intent?.getStringExtra(AlarmScheduler.EXTRA_ID)?.let { AlarmLog.idOf(it) } ?: "")
        setContent {
            val appearance by app.prefs.appearance.collectAsStateWithLifecycle(Appearance())
            val state by AlarmService.state.collectAsStateWithLifecycle()
            var seen by remember { mutableStateOf(false) }
            // Close when the alarm ends. Give the service a few seconds to publish if we were opened first.
            LaunchedEffect(state) {
                if (state != null) { seen = true; return@LaunchedEffect }
                delay(if (seen) 200 else 4000)
                if (AlarmService.state.value == null) finish()
            }
            MotifTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    state?.let { s ->
                        RingingScreen(
                            s,
                            onSnooze = { AlarmService.instance?.snooze() ?: AlarmService.send(this, AlarmService.ACTION_SNOOZE) },
                            onDismiss = { AlarmService.instance?.dismiss() ?: AlarmService.send(this, AlarmService.ACTION_DISMISS) },
                            onPrev = { AlarmService.send(this, AlarmService.ACTION_PREV) },
                            onNext = { AlarmService.send(this, AlarmService.ACTION_NEXT) },
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) AlarmService.instance?.snooze() ?: AlarmService.send(this, AlarmService.ACTION_SNOOZE)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        fun intent(context: Context, alarmId: String) = Intent(context, RingingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun RingingScreen(s: AlarmService.RingState, onSnooze: () -> Unit, onDismiss: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val a = s.alarm
    val dt = LocalDateTime.now()
    val song = a.songs.getOrNull(s.songIndex)
    val fadeDone = (s.fadeProgress * a.fadeSeconds).toInt()
    val left = s.endsAt?.let { (it - now).coerceAtLeast(0) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dt.format(DateTimeFormatter.ofPattern("EEEE, MMM d")).uppercase(), fontSize = 13.sp, letterSpacing = 1.sp, color = motif.textMuted)
            Text(dt.format(DateTimeFormatter.ofPattern("h:mm")), fontSize = 84.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, color = motif.text, lineHeight = 84.sp)
            val fadeText = if (a.fadeSeconds > 0 && !s.usingTone) " · fading in ${formatClock(fadeDone * 1000L)} of ${formatClock(a.fadeSeconds * 1000L)} · ${s.currentVolumePct}%" else ""
            Text(a.name.ifBlank { "Alarm" } + fadeText, fontSize = 13.sp, color = motif.textMuted, textAlign = TextAlign.Center)
            if (s.usingTone) Text("Spotify did not answer; playing the built-in tone.", fontSize = 12.sp, color = motif.textMuted)
            s.outputName?.let { Text("Playing on $it", fontSize = 12.sp, color = motif.textMuted) }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(180.dp)
                    .shadow(if (motif.glowIntensity > 0f) (8 + 32 * motif.glowIntensity).dp else 0.dp, RoundedCornerShape(12.dp), ambientColor = motif.glow, spotColor = motif.glow)
                    .clip(RoundedCornerShape(12.dp))
                    .background(motif.card)
                    .border(1.dp, motif.line, RoundedCornerShape(12.dp)),
            ) {
                if (song?.imageUrl != null) AsyncImage(song.imageUrl, null, Modifier.fillMaxSize())
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song?.title ?: "No songs", style = MaterialTheme.typography.titleMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val leftText = left?.let { " · ${formatClock(it)} left" } ?: ""
                Text(
                    (song?.artist?.let { "$it · " } ?: "") + "song ${s.songIndex + 1} of ${a.songs.size}$leftText",
                    fontSize = 13.sp, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIcon(Icons.Filled.SkipPrevious, "Previous", onPrev)
                RoundIcon(Icons.Filled.SkipNext, "Next", onNext)
            }
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(CircleShape)
                    .border(1.dp, motif.accent, CircleShape)
                    .clickable(onClick = onSnooze),
                contentAlignment = Alignment.Center,
            ) { Text("Snooze ${a.snoozeMinutes} min", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = motif.accent) }
            PillButton("Dismiss", filled = false, onClick = onDismiss)
            Text("Volume buttons also snooze", fontSize = 12.sp, color = motif.textDim)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.dp, motif.chipLine, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = motif.textMuted, modifier = Modifier.size(20.dp)) }
}
