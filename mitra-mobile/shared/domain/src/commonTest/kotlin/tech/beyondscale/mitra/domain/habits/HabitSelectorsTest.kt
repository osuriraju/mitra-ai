package tech.beyondscale.mitra.domain.habits

import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.THREE_A_WEEK
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HabitSelectorsTest {
    @Test fun streakCountsFromYesterdayWhenTodayOpen() {
        val h = habit(logs = logs("2026-10-11", "2026-10-12", "2026-10-13"))
        assertEquals(3, streak(h, TODAY))
        assertEquals(4, streak(h.copy(logs = h.logs + ("2026-10-14" to 1)), TODAY))
        // just after local midnight the new day is open, so yesterday's run still counts
        assertEquals(4, streak(h.copy(logs = h.logs + ("2026-10-14" to 1)), "2026-10-15"))
        assertEquals(0, streak(h, "2026-10-16"))
    }

    @Test fun streakSkipsRestDays() {
        val h = habit(days = WEEKDAYS, logs = logs("2026-10-08", "2026-10-09", "2026-10-12", "2026-10-13"))
        assertEquals(4, streak(h, TODAY))
    }

    @Test fun weekDotsAreMondayBased() {
        val h = habit(logs = logs("2026-10-12", "2026-10-14", "2026-10-11"))
        assertEquals(listOf(true, false, true, false, false, false, false), weekDots(h, TODAY))
        // Sunday still belongs to the week that started the Monday before
        assertEquals(listOf(true, false, true, false, false, false, false), weekDots(h, "2026-10-18"))
    }

    @Test fun weekRateRoundsLikeJs() {
        assertEquals(71, weekRate(habit(logs = logs("2026-10-08", "2026-10-09", "2026-10-10", "2026-10-12", "2026-10-14")), TODAY))
        val monWed = listOf(true, false, true, false, false, false, false)
        assertEquals(50, weekRate(habit(days = monWed, logs = logs("2026-10-12")), TODAY))
        assertEquals(0, weekRate(habit(days = List(7) { false }), TODAY))
    }

    @Test fun rateDaysCountsDueAndDone() {
        val h = habit(days = WEEKDAYS).withFirstDueLogged(11)
        assertEquals(Rate(due = 22, done = 11, pct = 50), rateDays(h, 30, TODAY))
        assertEquals(Rate(0, 0, 0), rateDays(habit(days = List(7) { false }), 30, TODAY))
    }

    @Test fun bestStreakLooksBack90Days() {
        val h = habit(logs = logs("2026-07-16", "2026-07-17", "2026-07-18", "2026-07-20", "2026-10-13"))
        assertEquals(3, bestStreak(h, TODAY))
        // 2026-07-15 is 91 days back — outside the window
        assertEquals(2, bestStreak(habit(logs = logs("2026-07-15", "2026-07-16", "2026-07-17")), TODAY))
    }

    @Test fun dueTodayExcludesArchivedAndRestDays() {
        val weekdays = noSugar
        val archived = sleepBy11.copy(id = "arch", archived = true, logs = lastDays(7) + ("2026-10-14" to 1))
        val daily = sleepBy11.copy(logs = logs("2026-10-08", "2026-10-09", "2026-10-10", "2026-10-12", "2026-10-14"))
        assertFalse(habitDue(weekdays, "2026-10-17"))
        assertTrue(habitDue(weekdays, TODAY))
        assertEquals(listOf("h-sugar", "h-sleep"), habitsDueToday(listOf(weekdays, archived, daily), TODAY).map { it.id })
        assertEquals(listOf("h-sleep"), habitsDueToday(listOf(weekdays, archived, daily), "2026-10-17").map { it.id })
        assertEquals(listOf("h-sleep"), habitsDoneToday(listOf(weekdays, archived, daily), TODAY).map { it.id })
        assertEquals(71, habitsWeekRate(listOf(daily, archived), TODAY))
        assertEquals(0, habitsWeekRate(listOf(archived), TODAY))
    }

    @Test fun longestStreakIgnoresArchivedAndIsNeverNegative() {
        val a = sleepBy11.copy(logs = lastDays(5))
        val b = noSugar.copy(id = "arch", archived = true, logs = lastDays(30))
        assertEquals(5, longestStreak(listOf(a, b), TODAY))
        assertEquals(0, longestStreak(emptyList(), TODAY))
    }

    @Test fun measurablePartialIsNotDone() {
        val h = drinkWater.copy(logs = mapOf(TODAY to 5))
        assertFalse(habitDone(h, TODAY))
        assertEquals(5, habitValue(h, TODAY))
        assertEquals(0, habitValue(h, "2026-10-13"))
        assertTrue(habitSubline(h, TODAY).text.startsWith("5/8 glasses · 8 glasses · daily"))
        assertEquals("5/8 glasses · 8 glasses · daily · Recovering — start again today", habitSubline(h, TODAY).text)
        assertTrue(habitDone(h.copy(logs = mapOf(TODAY to 9)), TODAY))
    }

    @Test fun sublineShowsRecovering() {
        val s = habitSubline(sleepBy11, TODAY)
        assertTrue(s.recovering)
        assertEquals("daily · Recovering — start again today", s.text)
        val streaking = habitSubline(sleepBy11.copy(logs = lastDays(12)), TODAY)
        assertFalse(streaking.recovering)
        assertEquals("daily · 🔥 12-day streak", streaking.text)
        assertEquals("0/20 min · 20 min · 3× a week · Recovering — start again today", habitSubline(read.copy(days = THREE_A_WEEK), TODAY).text)
    }

    @Test fun scheduleLabelsAndStep() {
        assertEquals("daily", scheduleLabel(sleepBy11))
        assertEquals("Mon–Fri", scheduleLabel(noSugar))
        assertEquals("Mon–Fri", scheduleLabel(noSugar.copy(days = WEEKDAYS.dropLast(1) + true))) // web checks Mon–Fri and Sat only
        assertEquals("3× a week", scheduleLabel(noSugar.copy(days = THREE_A_WEEK)))
        assertEquals("5× a week", cadenceLabel(noSugar))
        assertEquals("daily", cadenceLabel(read))
        assertEquals(1, stepFor(drinkWater))
        assertEquals(10, stepFor(read))
        assertEquals(15, stepFor(morningWalk))
        assertEquals(1, stepFor(habit(type = HabitType.Quantity, target = 1)))
        assertEquals(2, stepFor(habit(type = HabitType.Quantity, target = 3))) // Math.round(1.5) = 2
    }
}
