package com.kirkisham.motif.alarm

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A song in an alarm's list: the URI plus cached display data so the editor needs no network. */
@Serializable
data class AlarmSong(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val imageUrl: String? = null,
)

enum class OrderMode { START_TO_END, REPEAT_FIRST, RANDOM_START }

/** One alarm (spec §3.8). Times are the phone's local time. */
@Serializable
data class Alarm(
    val id: String,
    val name: String,
    val hour: Int,
    val minute: Int,
    /** True: fires once on [date]. False: repeats on [days]. */
    val oneTime: Boolean,
    /** ISO date (yyyy-MM-dd) for one-time alarms. */
    val date: String? = null,
    /** DayOfWeek values 1 (Mon) .. 7 (Sun) for recurring alarms. */
    val days: Set<Int> = emptySet(),
    val songs: List<AlarmSong> = emptyList(),
    val order: OrderMode = OrderMode.START_TO_END,
    /** 0 .. length of the first song. */
    val fadeSeconds: Int = 0,
    /** 0..100 % of the media stream's max. */
    val startVolume: Int = 10,
    val endVolume: Int = 65,
    /** Minutes to play; null means until dismissed. Default 10. */
    val playForMinutes: Int? = 10,
    val snoozeMinutes: Int = 10,
    val enabled: Boolean = true,
) {
    val time: LocalTime get() = LocalTime.of(hour, minute)
    val totalMs: Long get() = songs.sumOf { it.durationMs }

    /** Next moment this alarm should fire, or null when it never will (disabled, past one-time, no days). */
    fun nextTrigger(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!enabled) return null
        val zone: ZoneId = now.zone
        if (oneTime) {
            val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val at = ZonedDateTime.of(LocalDateTime.of(d, time), zone)
            return if (at.isAfter(now)) at else null
        }
        if (days.isEmpty()) return null
        for (i in 0..7) {
            val day = now.toLocalDate().plusDays(i.toLong())
            if (day.dayOfWeek.value !in days) continue
            val at = ZonedDateTime.of(LocalDateTime.of(day, time), zone)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** "6:30 AM · Mon to Fri · 3 songs · fade 1:30" for the saved list. */
    fun summary(): String {
        val t = formatTime(hour, minute)
        val rep = if (oneTime) (date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { "${it.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)} ${it.dayOfMonth}" } ?: "One time") else daysLabel(days)
        val n = if (songs.size == 1) "1 song" else "${songs.size} songs"
        return "$t · $rep · $n · fade ${fadeSeconds / 60}:${"%02d".format(fadeSeconds % 60)}"
    }

    companion object {
        fun formatTime(hour: Int, minute: Int): String {
            val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
            return "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
        }

        fun daysLabel(days: Set<Int>): String {
            if (days.isEmpty()) return "No days"
            val sorted = days.sorted()
            if (sorted == listOf(1, 2, 3, 4, 5)) return "Mon to Fri"
            if (sorted == listOf(6, 7)) return "Sat, Sun"
            if (sorted.size == 7) return "Every day"
            return sorted.joinToString(", ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        }

        /** The default for a new alarm: the next 6:30 on weekdays. */
        fun blank(id: String) = Alarm(
            id = id, name = "", hour = 6, minute = 30, oneTime = false,
            days = setOf(1, 2, 3, 4, 5), date = LocalDate.now().plusDays(1).toString(),
        )
    }
}
