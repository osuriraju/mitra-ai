package tech.beyondscale.mitra.domain.wellness

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.toFixed
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import kotlin.math.abs

/*
 * Ports of the wellness block of mitra-frontend/src/store/selectors.ts and the derived numbers in
 * screens/wellness.tsx. Every function takes the device's local `today` explicitly.
 */

private fun hhmmToMin(t: String): Int? {
    val p = t.split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}

/** Minutes between bed and wake, wrapping past midnight; 0 when either is missing (web `sleepMinutes`). */
fun sleepMinutes(start: String?, end: String?): Int {
    if (start.isNullOrEmpty() || end.isNullOrEmpty()) return 0
    val s = hhmmToMin(start) ?: return 0
    val e = hhmmToMin(end) ?: return 0
    var m = e - s
    if (m < 0) m += 1440
    return m
}

fun WellnessEntry?.sleepMin(): Int = if (this == null) 0 else sleepMinutes(sleepStart, sleepEnd)

/** kg / m², one decimal; 0 when either is missing or zero (web `bmi`). */
fun bmi(weightKg: Double?, heightCm: Number?): Double {
    val w = weightKg ?: return 0.0
    val h = heightCm?.toDouble() ?: return 0.0
    if (w == 0.0 || h == 0.0) return 0.0
    val m = h / 100
    return toFixed(w / (m * m), 1).toDouble()
}

/** Web `bmiBand`. */
fun bmiBand(v: Double): String = when {
    v == 0.0 -> "—"
    v < 18.5 -> "Under"
    v < 25 -> "Normal range"
    v < 30 -> "Over"
    else -> "High"
}

/** Index 0..3 of the lit BMI segment (<18.5, 18.5–24.9, 25–29.9, 30+), or -1 for no BMI. */
fun bmiSegment(v: Double): Int = when {
    v <= 0.0 -> -1
    v < 18.5 -> 0
    v < 25 -> 1
    v < 30 -> 2
    else -> 3
}

/** 450 → "7h 30m" (web `hm`, always both parts). */
fun hm(mins: Int): String = "${mins / 60}h ${(mins % 60).toString().padStart(2, '0')}m"

/** Consecutive days with a mood logged, counting back from today; an empty today does not break it (web `streakOf`). */
fun checkinStreak(entries: Map<String, WellnessEntry>, today: String): Int {
    var n = 0
    for (i in 0 until 400) {
        val d = addDays(today, -i)
        if (entries[d]?.mood != null) n++ else if (i > 0) break
    }
    return n
}

/** "Target 7h 30m · 1h 00m under" / " · on target" (check-in sleep hint). */
fun sleepTargetHint(sleepMin: Int, target: Int): String = "Target ${hm(target)}" + when {
    sleepMin != 0 && sleepMin < target -> " · ${hm(target - sleepMin)} under"
    sleepMin != 0 -> " · on target"
    else -> ""
}

/** The latest weight logged before [today], or null. */
fun lastWeightBefore(entries: Map<String, WellnessEntry>, today: String): Double? =
    entries.keys.filter { it < today && (entries[it]?.weight ?: 0.0) != 0.0 }.maxOrNull()?.let { entries[it]?.weight }

/** "Last 72.5 kg · +0.5 kg" / "Last 72.5 kg · no change"; empty without a previous weight (check-in hint). */
fun weightHint(last: Double?, current: Double?): String {
    if (last == null || last == 0.0) return ""
    val diff = (current ?: 0.0) - last
    val change = if (current != null && current != 0.0 && diff != 0.0) "${if (diff > 0) "+" else ""}${toFixed(diff, 1)} kg" else "no change"
    return "Last ${jsNum(last)} kg · $change"
}

/** The 7 nights ending on [day], hours to one decimal, 0 when not logged (sleep detail "This week"). */
fun sleepWeek(entries: Map<String, WellnessEntry>, day: String): List<Double> =
    (0 until 7).map { i -> toFixed(entries[addDays(day, -(6 - i))].sleepMin() / 60.0, 1).toDouble() }

/** Average of the logged nights, one decimal as a string ("7.2"), "0" when none (sleep detail). */
fun sleepWeekAvg(week: List<Double>): String {
    val logged = week.filter { it != 0.0 }
    return if (logged.isEmpty()) "0" else toFixed(week.sum() / logged.size, 1)
}

/** Everything the Trends screen derives for a range of [days] (7, 30 or 90) ending today. */
data class WellnessTrends(
    val days: Int,
    /** ISO day per point, oldest first ([days] long). */
    val dates: List<String>,
    /** Hours slept per day, 0 when not logged. */
    val sleepHours: List<Double>,
    /** Chart values: missing nights filled with the average, one decimal. */
    val sleepChart: List<Double>,
    val nightsLogged: Int,
    val avgSleepHours: Double,
    /** Average over the previous [days], 0 when none. */
    val prevSleepHours: Double,
    /** Last 14 days' steps (fewer when [days] is 7). */
    val steps: List<Int>,
    val avgSteps: Int,
    /** Mood per day, null when not logged. */
    val moods: List<Int?>,
    /** Average on the 1..5 scale, null without check-ins. */
    val moodAvg: Double?,
    val checkins: Int,
    /** Weights logged in the range, oldest first. */
    val weights: List<Double>,
    /** Heatmap levels 0..4 per day (mood + 1 capped at 4; 0 when not logged). */
    val moodHeat: List<Int>,
    /** Average "things done" after nights of <6.5h, 6.5–7.5h and >7.5h. */
    val sleepVsDone: List<Double>,
) {
    val firstWeight: Double? get() = weights.firstOrNull()
    val lastWeight: Double? get() = weights.lastOrNull()
}

/**
 * Web `WellnessTrendsScreen` maths. [doneOn] counts tasks completed on an ISO day (`completedAt == day`).
 */
fun wellnessTrends(entries: Map<String, WellnessEntry>, today: String, days: Int, doneOn: (String) -> Int): WellnessTrends {
    val dates = (0 until days).map { addDays(today, -(days - 1 - it)) }
    val es = dates.map { entries[it] }
    val sleeps = es.map { it.sleepMin() / 60.0 }
    val withSleep = sleeps.filter { it != 0.0 }
    val avgSleep = if (withSleep.isEmpty()) 0.0 else withSleep.sum() / withSleep.size
    val prev = (0 until days).mapNotNull { entries[addDays(today, -(2 * days - 1 - it))] }
        .map { it.sleepMin() / 60.0 }.filter { it != 0.0 }
    val prevSleep = if (prev.isEmpty()) 0.0 else prev.sum() / prev.size
    val steps = es.takeLast(14).map { it?.steps ?: 0 }
    val stepsLogged = steps.count { it != 0 }
    val avgSteps = if (stepsLogged == 0) 0 else jsRound(steps.sum().toDouble() / stepsLogged).toInt()
    val moods = es.map { it?.mood }
    val moodVals = moods.filterNotNull()
    val moodAvg = if (moodVals.isEmpty()) null else moodVals.sumOf { it + 1 }.toDouble() / moodVals.size
    val weights = es.mapNotNull { e -> e?.weight?.takeIf { it != 0.0 } }
    val buckets = listOf(0.0 to 6.5, 6.5 to 7.5, 7.5 to 24.0).map { (lo, hi) ->
        val ds = dates.indices.filter { val h = sleeps[it]; h >= lo && h < hi && h > 0 }
        val done = ds.map { doneOn(dates[it]) + jsRound((es[it]?.steps ?: 0) / 3000.0).toInt() }
        if (ds.isEmpty()) 0.0 else toFixed(done.sum().toDouble() / ds.size, 1).toDouble()
    }
    return WellnessTrends(
        days = days,
        dates = dates,
        sleepHours = sleeps,
        sleepChart = sleeps.map { toFixed(if (it != 0.0) it else avgSleep, 1).toDouble() },
        nightsLogged = withSleep.size,
        avgSleepHours = avgSleep,
        prevSleepHours = prevSleep,
        steps = steps,
        avgSteps = avgSteps,
        moods = moods,
        moodAvg = moodAvg,
        checkins = moodVals.size,
        weights = weights,
        moodHeat = moods.map { if (it == null) 0 else minOf(it + 1, 4) },
        sleepVsDone = buckets,
    )
}

/** "+12m vs previous" / "−5m vs previous"; empty without a previous period (Avg sleep KPI). */
fun sleepDeltaLabel(t: WellnessTrends): String {
    if (t.prevSleepHours == 0.0) return ""
    val d = t.avgSleepHours - t.prevSleepHours
    return "${if (d >= 0) "+" else "−"}${jsRound(abs(d) * 60)}m vs previous"
}

/** "3.8 / 5" or "—" (Mood avg KPI). */
fun moodAvgLabel(t: WellnessTrends): String = t.moodAvg?.let { "${toFixed(it, 1)} / 5" } ?: "—"

/** "72 kg · -1.5 kg in 30d"; empty with fewer than one weight (BMI KPI sub-label). */
fun weightDeltaLabel(t: WellnessTrends): String {
    val w0 = t.firstWeight ?: return ""
    val w1 = t.lastWeight ?: return ""
    val d = w1 - w0
    return "${jsNum(w1)} kg · ${if (d >= 0) "+" else ""}${toFixed(d, 1)} kg in ${t.days}d"
}

/** The insight next to the sleep-vs-productivity bars. */
fun sleepVsDoneInsight(t: WellnessTrends): String {
    val b = t.sleepVsDone
    return if (b[2] > b[0]) {
        "After 7.5h+ of sleep you did ${jsNum(b[2])} things on average vs ${jsNum(b[0])} after short nights."
    } else {
        "Not enough sleep variation yet to see a pattern."
    }
}

/** Current BMI for the trends KPI (latest weight in range × settings height), 0 when none. */
fun trendsBmi(t: WellnessTrends, s: WellnessSettings): Double = bmi(t.lastWeight, s.height)
