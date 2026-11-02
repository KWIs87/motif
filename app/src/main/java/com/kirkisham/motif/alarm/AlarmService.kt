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
 * play-for window, and stops when it ends. If Spotify has not reported playback within 10 s,
 * a generated tone plays on the alarm stream at end volume instead.
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = MotifApp.get(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ID)
        when (intent?.action) {
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACTION_DISMISS -> { dismiss(); return START_NOT_STICKY }
            ACTION_NEXT -> { scope.launch { step(+1) }; return START_NOT_STICKY }
            ACTION_PREV -> { scope.launch { step(-1) }; return START_NOT_STICKY }
        }
        if (id == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundNow(id)
        scope.launch { ring(id) }
        return START_NOT_STICKY
    }

    private fun startForegroundNow(id: String) {
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
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Snooze", servicePending(ACTION_SNOOZE))
            .addAction(0, "Dismiss", servicePending(ACTION_DISMISS))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    private suspend fun ring(id: String) {
        val a = app.alarmStore.get(id)
        if (a == null) { finish(); return }
        alarm = a
        startedAt = System.currentTimeMillis()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motif:alarm").also { it.acquire(2 * 60 * 60 * 1000L) }

        // Volume: remember the user's levels, start at the start volume.
        originalMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        setMusicPct(a.startVolume)

        playOrder = buildOrder(a)
        orderPos = 0
        publish(a, 0f, a.startVolume)

        // Show the ringing screen (full-screen intent above covers the lock screen; this covers the unlocked case).
        runCatching { startActivity(RingingActivity.intent(this, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

        runJob = scope.launch {
            val started = startSpotify(a)
            if (!started) startTone(a)
            // Fade: linear ramp from start to end volume over fadeSeconds (media stream only).
            launch {
                val fadeMs = a.fadeSeconds * 1000L
                if (!usingTone && fadeMs > 0) {
                    val t0 = System.currentTimeMillis()
                    while (isActive) {
                        val f = ((System.currentTimeMillis() - t0).toFloat() / fadeMs).coerceIn(0f, 1f)
                        val pct = (a.startVolume + (a.endVolume - a.startVolume) * f).roundToInt()
                        setMusicPct(pct)
                        publish(a, f, pct)
                        if (f >= 1f) break
                        delay(1000)
                    }
                } else if (!usingTone) {
                    setMusicPct(a.endVolume); publish(a, 1f, a.endVolume)
                }
            }
            // Play-for window.
            launch {
                val minutes = a.playForMinutes
                if (minutes != null) {
                    delay(minutes * 60_000L)
                    Log.i(TAG, "Play-for window ended")
                    dismiss()
                }
            }
            // Song advance loop (only when Spotify is doing the playing).
            if (!usingTone) advanceLoop(a)
        }
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

    /** Connects and plays the first song; true when a playing PlayerState for it arrived within 10 s. */
    private suspend fun startSpotify(a: Alarm): Boolean {
        if (a.songs.isEmpty() || !app.remote.isSpotifyInstalled) return false
        app.remote.acquire(HOLD)
        val deadline = System.currentTimeMillis() + 10_000
        val connected = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
            app.remote.connection.first { it == SpotifyRemote.Connection.Connected }
        } != null
        if (!connected) return false
        if (!playCurrent(a)) return false
        val playing = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1500)) {
            app.remote.playback.first { it != null && it.uri == expectedUri && it.isPlaying }
        } != null
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

    private fun startTone(a: Alarm) {
        usingTone = true
        Log.w(TAG, "Spotify did not answer within 10 s; playing the fallback tone")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, (max * a.endVolume / 100f).roundToInt().coerceIn(1, max), 0) }
        tone.start()
        publish(a, 1f, a.endVolume)
    }

    private fun setMusicPct(pct: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct.coerceIn(0, 100) / 100f).roundToInt(), 0) }
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
        Log.i(TAG, "Snooze")
        stopSound()
        app.alarmScheduler.snooze(a.id, a.snoozeMinutes)
        _state.value = _state.value?.copy(snoozed = true)
        finish(afterFire = true)
    }

    fun dismiss() {
        Log.i(TAG, "Dismiss")
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
                // One-time alarms switch themselves off; recurring ones move to their next day.
                if (a.oneTime) app.alarmStore.setEnabled(a.id, false)
                app.alarmScheduler.schedule(app.alarmStore.get(a.id) ?: a)
            }
            delay(400) // let the ringing screen read the final state
            _state.value = null
            app.remote.release(HOLD)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        tone.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        private const val TAG = "MotifAlarm"
        private const val CHANNEL = "alarm"
        private const val NOTIF_ID = 41
        private const val HOLD = "alarm"
        const val ACTION_SNOOZE = "com.kirkisham.motif.alarm.SNOOZE"
        const val ACTION_DISMISS = "com.kirkisham.motif.alarm.DISMISS"
        const val ACTION_NEXT = "com.kirkisham.motif.alarm.NEXT"
        const val ACTION_PREV = "com.kirkisham.motif.alarm.PREV"

        private val _state = MutableStateFlow<RingState?>(null)
        /** What the ringing screen shows; null when no alarm is ringing. */
        val state: StateFlow<RingState?> = _state.asStateFlow()

        @Volatile var instance: AlarmService? = null
            private set

        fun start(context: Context, alarmId: String) {
            val i = Intent(context, AlarmService::class.java).putExtra(AlarmScheduler.EXTRA_ID, alarmId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
