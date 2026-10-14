package tech.beyondscale.mitra.domain.wellness

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessMetric
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.model.applyChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TODAY = "2026-10-14"

class WellnessSelectorsTest {
    private fun day(n: Int, sleep: Pair<String, String>? = null, mood: Int? = null, steps: Int? = null, weight: Double? = null) =
        addDays(TODAY, -n).let { WellnessEntry(it, mood, sleep?.first, sleep?.second, steps, null, weight) }

    @Test fun sleepMinutesWrapsMidnight() {
        assertEquals(420, sleepMinutes("23:30", "06:30"))
        assertEquals(60, sleepMinutes("01:00", "02:00"))
        assertEquals(0, sleepMinutes(null, "06:30"))
        assertEquals(0, sleepMinutes("", "06:30"))
        assertEquals(0, sleepMinutes("07:00", "07:00"))
    }

    @Test fun bmiAndBands() {
        assertEquals(22.9, bmi(70.0, 175))
        assertEquals(0.0, bmi(null, 175))
        assertEquals(0.0, bmi(70.0, 0))
        assertEquals("—", bmiBand(0.0))
        assertEquals("Under", bmiBand(18.4))
        assertEquals("Normal range", bmiBand(18.5))
        assertEquals("Over", bmiBand(25.0))
        assertEquals("High", bmiBand(30.0))
        assertEquals(listOf(-1, 0, 1, 2, 3), listOf(0.0, 17.0, 22.0, 27.0, 31.0).map(::bmiSegment))
    }

    @Test fun hmAndHints() {
        assertEquals("7h 30m", hm(450))
        assertEquals("0h 05m", hm(5))
        assertEquals("Target 7h 30m · 0h 30m under", sleepTargetHint(420, 450))
        assertEquals("Target 7h 30m · on target", sleepTargetHint(480, 450))
        assertEquals("Target 7h 30m", sleepTargetHint(0, 450))
        assertEquals("Last 72.5 kg · +0.5 kg", weightHint(72.5, 73.0))
        assertEquals("Last 72 kg · no change", weightHint(72.0, null))
        assertEquals("", weightHint(null, 70.0))
    }

    @Test fun streakSkipsAnEmptyToday() {
        val e = listOf(day(1, mood = 2), day(2, mood = 3), day(4, mood = 1)).associateBy { it.date }
        assertEquals(2, checkinStreak(e, TODAY))
        assertEquals(3, checkinStreak(e + (TODAY to day(0, mood = 4)), TODAY))
        assertEquals(0, checkinStreak(emptyMap(), TODAY))
    }

    @Test fun lastWeightIsBeforeToday() {
        val e = listOf(day(0, weight = 70.0), day(3, weight = 71.0), day(9, weight = 72.0), day(1)).associateBy { it.date }
        assertEquals(71.0, lastWeightBefore(e, TODAY))
        assertNull(lastWeightBefore(emptyMap(), TODAY))
    }

    @Test fun sleepWeekAndAverage() {
        val e = listOf(day(0, "23:00" to "06:00"), day(2, "00:00" to "08:30")).associateBy { it.date }
        val week = sleepWeek(e, TODAY)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 8.5, 0.0, 7.0), week)
        assertEquals("7.8", sleepWeekAvg(week))
        assertEquals("0", sleepWeekAvg(List(7) { 0.0 }))
    }

    @Test fun trendsForAWeek() {
        val e = listOf(
            day(0, "23:00" to "07:00", mood = 4, steps = 9000, weight = 70.0),
            day(1, "00:00" to "06:00", mood = 1, steps = 3000),
            day(3, "23:00" to "06:00", weight = 71.5),
            day(8, "22:00" to "06:00"),
        ).associateBy { it.date }
        val t = wellnessTrends(e, TODAY, 7) { d -> if (d == TODAY) 2 else 0 }
        assertEquals(7, t.days)
        assertEquals(addDays(TODAY, -6), t.dates.first())
        assertEquals(listOf(0.0, 0.0, 0.0, 7.0, 0.0, 6.0, 8.0), t.sleepHours)
        assertEquals(3, t.nightsLogged)
        assertEquals(7.0, t.avgSleepHours)
        assertEquals(8.0, t.prevSleepHours)
        assertEquals("−60m vs previous", sleepDeltaLabel(t))
        assertEquals(listOf(7.0, 7.0, 7.0, 7.0, 7.0, 6.0, 8.0), t.sleepChart)
        assertEquals(7, t.steps.size)
        assertEquals(6000, t.avgSteps)
        assertEquals(3.5, t.moodAvg)
        assertEquals("3.5 / 5", moodAvgLabel(t))
        assertEquals(2, t.checkins)
        assertEquals(listOf(71.5, 70.0), t.weights)
        assertEquals("70 kg · -1.5 kg in 7d", weightDeltaLabel(t))
        assertEquals(listOf(0, 0, 0, 0, 0, 2, 4), t.moodHeat)
        // <6.5h: the 6h night (0 done + 1 for 3000 steps); 6.5–7.5h: the 7h night; >7.5h: today (2 + 3).
        assertEquals(listOf(1.0, 0.0, 5.0), t.sleepVsDone)
        assertEquals("After 7.5h+ of sleep you did 5 things on average vs 1 after short nights.", sleepVsDoneInsight(t))
        assertEquals(22.9, trendsBmi(t, WellnessSettings(height = 175)))
    }

    @Test fun emptyTrends() {
        val t = wellnessTrends(emptyMap(), TODAY, 30) { 0 }
        assertEquals(0, t.avgSteps)
        assertNull(t.moodAvg)
        assertEquals("—", moodAvgLabel(t))
        assertEquals("", sleepDeltaLabel(t))
        assertEquals("", weightDeltaLabel(t))
        assertEquals(14, t.steps.size)
        assertEquals("Not enough sleep variation yet to see a pattern.", sleepVsDoneInsight(t))
    }

    @Test fun changesMergeLikeTheServer() {
        val e = null.applyChanges("2026-10-14", TODAY, listOf(WellnessChange.Mood(3), WellnessChange.Water(4)))
        assertEquals(WellnessEntry("2026-10-14", mood = 3, water = 4, savedAt = TODAY), e)
        assertEquals(e.copy(mood = null), e.applyChanges("2026-10-14", "2026-10-15", listOf(WellnessChange.Mood(null))))
        val s = WellnessSettings().applyChanges(listOf(WellnessSettingsChange.Track(WellnessMetric.Bmi, false), WellnessSettingsChange.Height(180)))
        assertEquals(false, s.track[WellnessMetric.Bmi])
        assertEquals(true, s.track[WellnessMetric.Mood])
        assertEquals(180, s.height)
    }
}
