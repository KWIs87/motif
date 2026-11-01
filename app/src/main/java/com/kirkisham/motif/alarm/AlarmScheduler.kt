package com.kirkisham.motif.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kirkisham.motif.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Puts alarms into AlarmManager with setAlarmClock (exact, visible to the system as an alarm,
 * survives Doze). One PendingIntent per alarm id; snoozes reuse the id with a snooze flag.
 */
class AlarmScheduler(private val context: Context, private val store: AlarmStore) {
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    /** Re-reads every alarm and (re)schedules the enabled ones. Call after any edit and on boot. */
    suspend fun rescheduleAll() {
        val alarms = store.all()
        for (a in alarms) schedule(a)
    }

    fun schedule(a: Alarm) {
        val pi = pending(a.id, snooze = false)
        val next = a.nextTrigger()
        if (next == null) { am.cancel(pi); return }
        setClock(next.toInstant().toEpochMilli(), pi)
        Log.i(TAG, "Alarm ${a.id.take(6)} set for $next")
    }

    fun cancel(id: String) {
        am.cancel(pending(id, snooze = false))
        am.cancel(pending(id, snooze = true))
    }

    /** One-shot snooze for [minutes]; leaves the regular schedule alone. */
    fun snooze(id: String, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        setClock(at, pending(id, snooze = true))
        Log.i(TAG, "Alarm ${id.take(6)} snoozed $minutes min")
    }

    private fun setClock(atMillis: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), pi)
        } catch (e: SecurityException) {
            // Exact alarms denied: fall back to an inexact alarm so something still fires.
            Log.w(TAG, "Exact alarm not allowed; using inexact")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun pending(id: String, snooze: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = if (snooze) ACTION_SNOOZE_FIRE else ACTION_FIRE
            putExtra(EXTRA_ID, id)
            data = android.net.Uri.parse("motif://alarm/$id/${if (snooze) "snooze" else "fire"}")
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "MotifAlarm"
        const val ACTION_FIRE = "com.kirkisham.motif.ALARM_FIRE"
        const val ACTION_SNOOZE_FIRE = "com.kirkisham.motif.ALARM_SNOOZE_FIRE"
        const val EXTRA_ID = "alarm_id"
    }
}

/** Wakes the foreground service when an alarm fires, and reschedules after boot or time changes. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE, AlarmScheduler.ACTION_SNOOZE_FIRE -> {
                val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
                AlarmService.start(context, id)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON", AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val result = goAsync()
                val store = AlarmStore(context)
                CoroutineScope(Dispatchers.Default).launch {
                    try { AlarmScheduler(context, store).rescheduleAll() } finally { result.finish() }
                }
            }
        }
    }
}
