package com.kirkisham.motif.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 0.12.1: the one-time "fires on the wrong day" report, pinned down with a fixed clock. Every case
 * runs [Alarm.nextTrigger] (what the scheduler and the list's "Rings in" use) and [Alarm.resolveForSave]
 * (what the editor's Save stamps) against an injected `now`, never the wall clock.
 */
class AlarmTriggerTest {
    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Sunday, 1 Nov 2026 (after the DST change the same morning, so no transition inside a test). */
    private fun at(hour: Int, minute: Int, date: LocalDate = LocalDate.of(2026, 11, 1)): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(date, LocalTime.of(hour, minute)), zone)

    private fun oneTime(hour: Int, minute: Int, date: String? = null, enabled: Boolean = true) =
        Alarm.blank("t").copy(hour = hour, minute = minute, oneTime = true, date = date, enabled = enabled)

    private fun weekly(hour: Int, minute: Int, days: Set<Int>) =
        Alarm.blank("w").copy(hour = hour, minute = minute, oneTime = false, days = days)

    // ---- one-time, no date picked (the editor leaves it null until the user opens the date sheet) ----

    @Test fun oneTime_timeLaterToday_noDate_firesToday() {
        val now = at(14, 0)
        assertEquals(at(22, 45), oneTime(22, 45).nextTrigger(now))
    }

    @Test fun oneTime_timeEarlierToday_noDate_firesTomorrow() {
        val now = at(14, 0)
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 2)), oneTime(6, 30).nextTrigger(now))
    }

    @Test fun oneTime_sameMinuteAsNow_firesTomorrow() {
        // 14:00:00 exactly is not "after" now; the next 14:00 is tomorrow.
        val now = at(14, 0)
        assertEquals(at(14, 0, LocalDate.of(2026, 11, 2)), oneTime(14, 0).nextTrigger(now))
    }

    @Test fun oneTime_at2240_for0630_firesTomorrow0630() {
        val now = at(22, 40)
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 2)), oneTime(6, 30).nextTrigger(now))
    }

    // ---- one-time with an explicit date ----

    @Test fun oneTime_explicitToday_timeLater_firesToday() {
        val now = at(14, 0)
        assertEquals(at(22, 45), oneTime(22, 45, date = "2026-11-01").nextTrigger(now))
    }

    @Test fun oneTime_explicitToday_timePast_rollsToTomorrow() {
        val now = at(14, 0)
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 2)), oneTime(6, 30, date = "2026-11-01").nextTrigger(now))
    }

    @Test fun oneTime_explicitFutureDate_isKept() {
        val now = at(14, 0)
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 5)), oneTime(6, 30, date = "2026-11-05").nextTrigger(now))
    }

    @Test fun oneTime_explicitPastDate_rollsToNextOccurrenceOfTime() {
        val now = at(14, 0)
        // Yesterday 22:45 is gone; the next 22:45 is tonight.
        assertEquals(at(22, 45), oneTime(22, 45, date = "2026-10-31").nextTrigger(now))
    }

    @Test fun oneTime_unparseableDate_behavesLikeNoDate() {
        val now = at(14, 0)
        assertEquals(at(22, 45), oneTime(22, 45, date = "tomorrow").nextTrigger(now))
    }

    @Test fun disabled_neverFires() {
        assertNull(oneTime(22, 45, enabled = false).nextTrigger(at(14, 0)))
    }

    // ---- what Save stamps (the editor's resolution) ----

    @Test fun save_oneTime_laterToday_stampsTodayAndEnables() {
        val now = at(14, 0)
        val saved = oneTime(22, 45, enabled = false).resolveForSave(now)
        assertEquals("2026-11-01", saved.date)
        assertEquals(true, saved.enabled)
        assertEquals(at(22, 45), saved.nextTrigger(now))
    }

    @Test fun save_oneTime_earlierToday_stampsTomorrow() {
        val now = at(22, 40)
        val saved = oneTime(6, 30).resolveForSave(now)
        assertEquals("2026-11-02", saved.date)
    }

    @Test fun save_oneTime_staleDateFromAnEarlierRing_stampsTheDayItWillRing() {
        // An alarm that rang this morning (date today, now disabled) re-saved for tonight must ring tonight.
        val now = at(14, 0)
        val saved = oneTime(22, 45, date = "2026-11-01", enabled = false).resolveForSave(now)
        assertEquals("2026-11-01", saved.date)
        assertEquals(at(22, 45), saved.nextTrigger(now))
    }

    @Test fun save_recurring_leavesDateAlone() {
        val saved = weekly(6, 30, setOf(1, 2, 3, 4, 5)).copy(date = "2026-11-01").resolveForSave(at(14, 0))
        assertEquals("2026-11-01", saved.date)
    }

    @Test fun save_thenScheduleAndList_agree() {
        // Save, the scheduler and the list's "Rings in" all read nextTrigger of the saved object.
        val now = at(21, 0)
        val saved = oneTime(21, 30).resolveForSave(now)
        val scheduled = saved.nextTrigger(now)
        assertEquals(at(21, 30), scheduled)
        assertEquals("Rings in 30 min", ringsInText(scheduled, now))
        assertEquals("Once · Nov 1", saved.repeatSummary(now))
    }

    // ---- weekday sets ----

    @Test fun weekdays_fromSundayAfternoon_nextIsMonday() {
        val now = at(14, 0) // Sunday
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 2)), weekly(6, 30, setOf(1, 2, 3, 4, 5)).nextTrigger(now))
    }

    @Test fun weekdays_fridayEvening_crossesWeekendToMonday() {
        val friday = LocalDate.of(2026, 11, 6)
        val now = at(22, 0, friday)
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 9)), weekly(6, 30, setOf(1, 2, 3, 4, 5)).nextTrigger(now))
    }

    @Test fun weekly_sameDayOnly_timePast_isNextWeek() {
        val now = at(14, 0) // Sunday 14:00
        assertEquals(at(6, 30, LocalDate.of(2026, 11, 8)), weekly(6, 30, setOf(7)).nextTrigger(now))
    }

    @Test fun weekly_sameDayOnly_timeLater_isToday() {
        val now = at(14, 0) // Sunday
        assertEquals(at(22, 0), weekly(22, 0, setOf(7)).nextTrigger(now))
    }

    @Test fun weekly_noDays_neverFires() {
        assertNull(weekly(6, 30, emptySet()).nextTrigger(at(14, 0)))
    }

    @Test fun ringsIn_texts() {
        val now = at(14, 0)
        assertEquals("Rings in 8 h 45 min", ringsInText(at(22, 45), now))
        assertEquals("Rings in 1 d 2 h", ringsInText(at(16, 0, LocalDate.of(2026, 11, 2)), now))
        assertEquals("Rings in under a minute", ringsInText(now.plusSeconds(20), now))
        assertNull(ringsInText(null, now))
    }
}
