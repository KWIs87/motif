package com.kirkisham.motif.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.kirkisham.motif.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Puts alarms into AlarmManager with setAlarmClock (exact, visible to the system as an alarm,
 * survives Doze). One PendingIntent per alarm id; snoozes reuse the id with a snooze flag.
 * Every scheduling step is written to [AlarmLog] (0.12).
 */
class AlarmScheduler(private val context: Context, private val store: AlarmStore) {
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    /**
     * Re-reads every alarm and (re)schedules the enabled ones. Runs on boot, time changes, after the
     * app is updated and at every process start (0.12.1): setAlarmClock with the same PendingIntent is
     * idempotent, so this only ever restores an alarm the system dropped (force stop, Samsung's app
     * sleep, an update) and never doubles one. One summary line in the log plus any roll-forward.
     */
    suspend fun rescheduleAll(reason: String = "") {
        val alarms = store.all()
        var set = 0
        for (a in alarms) if (schedule(a, quiet = true)) set++
        AlarmLog.log(context, "rescheduled", "$set of ${alarms.size} alarms set" + if (reason.isNotBlank()) " ($reason)" else "")
    }

    /** Sets or cancels one alarm from [a]'s own next trigger. Returns true when a time was set. */
    fun schedule(a: Alarm, quiet: Boolean = false): Boolean {
        val pi = pending(a.id, snooze = false)
        val now = ZonedDateTime.now()
        val next = a.nextTrigger(now)
        if (next == null) {
            am.cancel(pi)
            if (!quiet) AlarmLog.log(context, "cancelled", "${AlarmLog.idOf(a.id)} (off or no next time)")
            return false
        }
        // A one-time alarm whose stored date is already behind us was missed (the system dropped the
        // alarm, or the phone was off): say so, since rolling it forward otherwise looks like a date bug.
        if (a.oneTime && a.enabled) {
            val stored = a.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            if (stored != null && stored != next.toLocalDate()) {
                AlarmLog.log(context, "missed", "${AlarmLog.idOf(a.id)} was for $stored ${Alarm.formatTime(a.hour, a.minute)}; rolled to ${next.toLocalDate()}")
            }
        }
        setClock(next.toInstant().toEpochMilli(), pi, a.id, "scheduled", quiet)
        return true
    }

    /**
     * Test alarm (0.12): the given transient alarm is saved and set for [atMillis] through the same
     * setAlarmClock path a real alarm takes, so the whole fire path is exercised.
     */
    suspend fun scheduleAt(a: Alarm, atMillis: Long) {
        store.save(a)
        setClock(atMillis, pending(a.id, snooze = false), a.id, "scheduled test")
    }

    fun cancel(id: String) {
        am.cancel(pending(id, snooze = false))
        am.cancel(pending(id, snooze = true))
        AlarmLog.log(context, "cancelled", AlarmLog.idOf(id))
    }

    /** One-shot snooze for [minutes]; leaves the regular schedule alone. */
    fun snooze(id: String, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        setClock(at, pending(id, snooze = true), id, "snoozed $minutes min")
    }

    private fun setClock(atMillis: Long, pi: PendingIntent, id: String, event: String, quiet: Boolean = false) {
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val whenText = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' ')
        try {
            if (!canScheduleExact()) throw SecurityException("exact alarms not allowed")
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), pi)
            if (!quiet) AlarmLog.log(context, event, "${AlarmLog.idOf(id)} for $whenText (exact)")
        } catch (e: SecurityException) {
            // Exact alarms denied: fall back to an inexact alarm so something still fires.
            Log.w(TAG, "Exact alarm not allowed; using inexact")
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                AlarmLog.log(context, event, "${AlarmLog.idOf(id)} for $whenText (INEXACT: exact alarms not allowed)")
            } catch (t: Throwable) {
                AlarmLog.error(context, "setClock", t)
            }
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

/**
 * Wakes the foreground service when an alarm fires, and reschedules after boot or time changes.
 * Not exported; our own PendingIntent names the component explicitly, which the system delivers
 * regardless, and the system broadcasts in the manifest filter are exempt from the export rule.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE, AlarmScheduler.ACTION_SNOOZE_FIRE -> {
                val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID)
                if (id == null) { AlarmLog.log(context, "receiver fired", "no alarm id in intent"); return }
                val snooze = intent.action == AlarmScheduler.ACTION_SNOOZE_FIRE
                AlarmLog.log(context, "receiver fired", AlarmLog.idOf(id) + if (snooze) " (snooze)" else "")
                // Hold the CPU until the service has its own lock: the broadcast's wake lock ends when
                // onReceive returns, and the service's onCreate runs after that.
                holdCpu(context)
                try {
                    AlarmService.start(context, id)
                    AlarmLog.log(context, "service start requested", AlarmLog.idOf(id))
                } catch (t: Throwable) {
                    // Android 12+: ForegroundServiceStartNotAllowedException when the start is treated as
                    // background. Exact alarms are exempt, so this means the alarm went inexact or was denied.
                    AlarmLog.error(context, "startForegroundService", t)
                    releaseCpu()
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON", AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val result = goAsync()
                val reason = intent.action?.substringAfterLast('.') ?: ""
                val store = AlarmStore(context)
                CoroutineScope(Dispatchers.Default).launch {
                    try { AlarmScheduler(context, store).rescheduleAll(reason) } catch (t: Throwable) { AlarmLog.error(context, "rescheduleAll", t) } finally { result.finish() }
                }
            }
        }
    }

    companion object {
        private var cpu: PowerManager.WakeLock? = null

        /** Partial wake lock bridging receiver → service, 60 s at most; the service releases it once it has its own. */
        @Synchronized fun holdCpu(context: Context) {
            if (cpu?.isHeld == true) return
            cpu = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motif:alarm-receiver").also { it.acquire(60_000L) }
        }

        @Synchronized fun releaseCpu() {
            cpu?.let { if (it.isHeld) runCatching { it.release() } }
            cpu = null
        }
    }
}

/** For the list: "Rings in 10 h 14 min" from a next trigger. */
fun ringsInText(next: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): String? {
    if (next == null) return null
    val d = java.time.Duration.between(now, next)
    if (d.isNegative) return "Rings now"
    val h = d.toHours(); val m = d.toMinutes() % 60
    return when {
        h >= 24 -> "Rings in ${h / 24} d ${h % 24} h"
        h > 0 -> "Rings in $h h $m min"
        m > 0 -> "Rings in $m min"
        else -> "Rings in under a minute"
    }
}
