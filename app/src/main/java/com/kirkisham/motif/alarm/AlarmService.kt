package com.kirkisham.motif.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.R
import com.kirkisham.motif.data.SpotifyRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Owns a ringing alarm (spec §3.8): connects App Remote, plays the alarm's songs in the chosen
 * order, ramps the media volume from start to end over the fade, loops the list to fill the
 * play-for window, and stops when it ends. If Spotify has not reported playback within 8 s, a
 * generated tone plays on the alarm stream with the same volume ramp instead (0.12). Every step
 * is written to [AlarmLog].
 */
class AlarmService : Service() {

    data class RingState(
        val alarm: Alarm,
        val startedAt: Long,
        val endsAt: Long?,
        val songIndex: Int,
        val fadeProgress: Float,
        val currentVolumePct: Int,
        val usingTone: Boolean,
        val outputName: String?,
        val snoozed: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: MotifApp
    private lateinit var audio: AudioManager
    private var alarm: Alarm? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val tone = TonePlayer()
    private var runJob: Job? = null
    private var originalMusicVolume = -1
    private var originalAlarmVolume = -1
    private var playOrder: List<Int> = emptyList()
    private var orderPos = 0
    private var expectedUri: String? = null
    private var startedAt = 0L
    private var usingTone = false
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = MotifApp.get(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        instance = this
        // Own CPU lock for the whole ring (2 h cap), then let the receiver's bridge lock go.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motif:alarm").also { runCatching { it.acquire(2 * 60 * 60 * 1000L) } }
        AlarmReceiver.releaseCpu()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ID)
        when (intent?.action) {
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACTION_DISMISS -> { dismiss(); return START_NOT_STICKY }
            ACTION_NEXT -> { scope.launch { step(+1) }; return START_NOT_STICKY }
            ACTION_PREV -> { scope.launch { step(-1) }; return START_NOT_STICKY }
        }
        if (id == null) { AlarmLog.log(this, "service started", "no alarm id; stopping"); stopSelf(); return START_NOT_STICKY }
        AlarmLog.log(this, "service started", AlarmLog.idOf(id))
        if (ringing) { AlarmLog.log(this, "service started", "already ringing; ignored"); return START_NOT_STICKY }
        ringing = true
        startForegroundNow(id)
        scope.launch {
            try { ring(id) } catch (t: Throwable) { AlarmLog.error(this@AlarmService, "ring", t); emergencyTone() }
        }
        return START_NOT_STICKY
    }

    /**
     * startForeground within the 5 s window, mediaPlayback type, high-importance channel, full-screen
     * intent to the ringing screen, CATEGORY_ALARM. Any failure is logged, never swallowed silently.
     */
    private fun startForegroundNow(id: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null); enableVibration(false); setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val fullScreen = PendingIntent.getActivity(
                this, 1, RingingActivity.intent(this, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Alarm")
                .setContentText("Motif alarm is ringing")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .addAction(0, "Snooze", servicePending(ACTION_SNOOZE))
                .addAction(0, "Dismiss", servicePending(ACTION_DISMISS))
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
            val notifOk = Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()
            val fsiOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            AlarmLog.log(this, "foreground ok", buildString {
                append("mediaPlayback")
                if (!notifOk) append("; notifications OFF (no banner, no lock-screen alarm)")
                if (!fsiOk) append("; full-screen intent not allowed")
            })
        } catch (t: Throwable) {
            AlarmLog.error(this, "startForeground", t)
        }
    }

    private suspend fun ring(id: String) {
        val a = app.alarmStore.get(id)
        if (a == null) { AlarmLog.log(this, "error", "${AlarmLog.idOf(id)} not found in store; stopping"); finish(); return }
        alarm = a
        startedAt = System.currentTimeMillis()

        // Volume: remember the user's levels, start at the start volume.
        originalMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        setMusicPct(a.startVolume)

        playOrder = buildOrder(a)
        orderPos = 0
        publish(a, 0f, a.startVolume)

        // Show the ringing screen. The notification's full-screen intent covers the locked / screen-off
        // case; this direct start only works while Motif is already in front (Android 10+ background rule).
        runCatching { startActivity(RingingActivity.intent(this, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { AlarmLog.error(this, "startActivity", it) }

        runJob = scope.launch {
            val started = try { startSpotify(a) } catch (t: Throwable) { AlarmLog.error(this@AlarmService, "startSpotify", t); false }
            if (!started) startTone(a)
            // Fade: linear ramp from start to end volume over fadeSeconds, on the stream that is sounding.
            launch {
                val fadeMs = a.fadeSeconds * 1000L
                if (fadeMs > 0) {
                    val t0 = System.currentTimeMillis()
                    while (isActive) {
                        val f = ((System.currentTimeMillis() - t0).toFloat() / fadeMs).coerceIn(0f, 1f)
                        val pct = (a.startVolume + (a.endVolume - a.startVolume) * f).roundToInt()
                        setPct(pct)
                        publish(a, f, pct)
                        if (f >= 1f) break
                        delay(1000)
                    }
                } else {
                    setPct(a.endVolume); publish(a, 1f, a.endVolume)
                }
            }
            // Play-for window.
            launch {
                val minutes = a.playForMinutes
                if (minutes != null) {
                    delay(minutes * 60_000L)
                    AlarmLog.log(this@AlarmService, "play-for ended", "$minutes min")
                    dismiss()
                }
            }
            // Song advance loop (only when Spotify is doing the playing).
            if (!usingTone) advanceLoop(a)
        }
    }

    /** Last resort when ring() itself threw before any sound: the tone at a safe fixed level. */
    private fun emergencyTone() {
        val a = alarm ?: Alarm.blank("emergency").copy(name = "Alarm", endVolume = 70, playForMinutes = 10)
        if (alarm == null) { alarm = a; startedAt = System.currentTimeMillis(); publish(a, 1f, a.endVolume) }
        if (!usingTone) { startTone(a); setPct(a.endVolume) }
        scope.launch { delay((a.playForMinutes ?: 10) * 60_000L); dismiss() }
    }

    private fun buildOrder(a: Alarm): List<Int> {
        val n = a.songs.size
        if (n == 0) return emptyList()
        return when (a.order) {
            OrderMode.START_TO_END -> (0 until n).toList()
            OrderMode.REPEAT_FIRST -> listOf(0)
            OrderMode.RANDOM_START -> { val s = Random.nextInt(n); (0 until n).map { (s + it) % n } }
        }
    }

    private fun currentSongIndex(): Int = playOrder.getOrNull(orderPos % playOrder.size.coerceAtLeast(1)) ?: 0

    /** Connects and plays the first song; true when a playing PlayerState for it arrived within [REMOTE_MS]. */
    private suspend fun startSpotify(a: Alarm): Boolean {
        if (a.songs.isEmpty()) { AlarmLog.log(this, "remote skipped", "no songs; tone"); return false }
        if (!app.remote.isSpotifyInstalled) { AlarmLog.log(this, "remote failed", "Spotify not installed"); return false }
        app.remote.acquire(HOLD)
        val deadline = System.currentTimeMillis() + REMOTE_MS
        val connected = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
            app.remote.connection.first { it == SpotifyRemote.Connection.Connected }
        } != null
        if (!connected) {
            val why = when (val c = app.remote.connection.value) {
                is SpotifyRemote.Connection.Failed -> c.reason
                SpotifyRemote.Connection.NotInstalled -> "not installed"
                SpotifyRemote.Connection.Connecting -> "still connecting"
                else -> c.toString()
            }
            AlarmLog.log(this, "remote failed", "not connected in ${REMOTE_MS / 1000} s ($why)")
            return false
        }
        AlarmLog.log(this, "remote connected", "${(REMOTE_MS - (deadline - System.currentTimeMillis())) / 1000.0} s")
        if (!playCurrent(a)) { AlarmLog.log(this, "remote failed", "play command rejected"); return false }
        val playing = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(2500)) {
            app.remote.playback.first { it != null && it.uri == expectedUri && it.isPlaying }
        } != null
        if (!playing) {
            AlarmLog.log(this, "remote failed", "no playback reported after play")
            scope.launch { runCatching { app.remote.pause() } } // in case it starts late, do not double up with the tone
        } else AlarmLog.log(this, "remote playing", "song ${currentSongIndex() + 1} of ${a.songs.size}")
        return playing
    }

    private suspend fun playCurrent(a: Alarm): Boolean {
        val song = a.songs.getOrNull(currentSongIndex()) ?: return false
        expectedUri = song.uri
        app.remote.setShuffle(false)
        app.remote.setRepeat(0)
        val ok = app.remote.play(song.uri)
        publish(a, null, null)
        return ok
    }

    /** Watches playback; when the current song finishes (or Spotify wanders off it), plays the next in order. */
    private suspend fun advanceLoop(a: Alarm) {
        var offSince = 0L
        while (scope.isActive) {
            delay(700)
            val p = app.remote.playback.value ?: continue
            val cur = expectedUri ?: continue
            val nearEnd = p.durationMs > 0 && p.positionNow() >= p.durationMs - 1200
            if (p.uri == cur) {
                offSince = 0
                if (nearEnd && p.isPaused) { step(+1); awaitSwitch() }
            } else {
                // Spotify moved to something else (autoplay, or the song ended into the next): take over again.
                if (offSince == 0L) offSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - offSince > 2500) { step(+1); offSince = 0; awaitSwitch() }
            }
        }
    }

    /** After a play command, wait until Spotify reports the new song (or give up after 6 s) so the loop does not double-skip. */
    private suspend fun awaitSwitch() {
        val want = expectedUri ?: return
        withTimeoutOrNull(6_000) { app.remote.playback.first { it?.uri == want } }
        delay(500)
    }

    private suspend fun step(delta: Int) {
        val a = alarm ?: return
        if (playOrder.isEmpty()) return
        orderPos = ((orderPos + delta) % playOrder.size + playOrder.size) % playOrder.size
        if (usingTone) { publish(a, null, null); return }
        playCurrent(a)
    }

    /** The built-in tone on the alarm stream (USAGE_ALARM); the ramp then runs on that stream. */
    private fun startTone(a: Alarm) {
        usingTone = true
        // Restore the media stream (the Spotify attempt may have lowered it) and start the alarm stream at the start volume.
        if (originalMusicVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0) }
        setAlarmPct(if (a.fadeSeconds > 0) a.startVolume else a.endVolume)
        val ok = tone.start()
        if (ok) AlarmLog.log(this, "tone fallback started", "alarm stream ${audio.getStreamVolume(AudioManager.STREAM_ALARM)}/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
        else AlarmLog.log(this, "error", "tone: AudioTrack did not initialise (${tone.lastError ?: "unknown"})")
        publish(a, null, null)
    }

    /** Volume on whichever stream is sounding: alarm for the tone, media for Spotify. */
    private fun setPct(pct: Int) { if (usingTone) setAlarmPct(pct) else setMusicPct(pct) }

    private fun setMusicPct(pct: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct.coerceIn(0, 100) / 100f).roundToInt(), 0) }
            .onFailure { AlarmLog.error(this, "setStreamVolume music", it) }
    }

    /** Never below index 1 so the tone is always audible. */
    private fun setAlarmPct(pct: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, (max * pct.coerceIn(0, 100) / 100f).roundToInt().coerceIn(1, max), 0) }
            .onFailure { AlarmLog.error(this, "setStreamVolume alarm", it) }
    }

    private fun publish(a: Alarm, fade: Float?, volumePct: Int?) {
        val prev = _state.value
        _state.value = RingState(
            alarm = a,
            startedAt = startedAt,
            endsAt = a.playForMinutes?.let { startedAt + it * 60_000L },
            songIndex = currentSongIndex(),
            fadeProgress = fade ?: prev?.fadeProgress ?: 0f,
            currentVolumePct = volumePct ?: prev?.currentVolumePct ?: a.startVolume,
            usingTone = usingTone,
            outputName = outputName(),
        )
    }

    private fun outputName(): String? {
        val devs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            devs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> devs.first { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            devs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "Headphones"
            else -> null
        }
    }

    fun snooze() {
        val a = alarm ?: run { finish(); return }
        AlarmLog.log(this, "snoozed", "${AlarmLog.idOf(a.id)} ${a.snoozeMinutes} min")
        stopSound()
        app.alarmScheduler.snooze(a.id, a.snoozeMinutes)
        _state.value = _state.value?.copy(snoozed = true)
        finish(afterFire = true)
    }

    fun dismiss() {
        AlarmLog.log(this, "dismissed", alarm?.let { AlarmLog.idOf(it.id) } ?: "")
        stopSound()
        finish(afterFire = true)
    }

    private fun stopSound() {
        runJob?.cancel(); runJob = null
        tone.stop()
        if (!usingTone) scope.launch { runCatching { app.remote.pause() } }
        if (originalMusicVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0) }
        if (originalAlarmVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0) }
    }

    private fun finish(afterFire: Boolean = false) {
        val a = alarm
        scope.launch {
            if (afterFire && a != null) {
                when {
                    // The test alarm leaves the store once it has rung (its snooze still finds it until then).
                    a.transient -> { if (_state.value?.snoozed != true) app.alarmStore.delete(a.id) }
                    // One-time alarms switch themselves off; recurring ones move to their next day.
                    a.oneTime -> { app.alarmStore.setEnabled(a.id, false); app.alarmScheduler.schedule(app.alarmStore.get(a.id) ?: a) }
                    else -> app.alarmScheduler.schedule(app.alarmStore.get(a.id) ?: a)
                }
            }
            delay(400) // let the ringing screen read the final state
            _state.value = null
            app.remote.release(HOLD)
            wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        tone.stop()
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        AlarmReceiver.releaseCpu()
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        private const val CHANNEL = "alarm"
        private const val NOTIF_ID = 41
        private const val HOLD = "alarm"
        /** How long Spotify gets to connect and start playing before the tone takes over. */
        private const val REMOTE_MS = 8_000L
        const val ACTION_SNOOZE = "com.kirkisham.motif.alarm.SNOOZE"
        const val ACTION_DISMISS = "com.kirkisham.motif.alarm.DISMISS"
        const val ACTION_NEXT = "com.kirkisham.motif.alarm.NEXT"
        const val ACTION_PREV = "com.kirkisham.motif.alarm.PREV"

        private val _state = MutableStateFlow<RingState?>(null)
        /** What the ringing screen shows; null when no alarm is ringing. */
        val state: StateFlow<RingState?> = _state.asStateFlow()

        @Volatile var instance: AlarmService? = null
            private set

        /** Throws (Android 12+) when the system treats the start as background; the receiver logs that. */
        fun start(context: Context, alarmId: String) {
            val i = Intent(context, AlarmService::class.java).putExtra(AlarmScheduler.EXTRA_ID, alarmId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
