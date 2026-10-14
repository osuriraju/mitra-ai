package tech.beyondscale.mitra.domain.habits

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType

/*
 * Ports of the habits block of mitra-frontend/src/store/selectors.ts (plus the HRow labels in screens/habits.tsx).
 * Every function takes the device's local `today` explicitly — never UTC.
 */

fun habitDue(h: Habit, date: String): Boolean = h.days.getOrElse(dowMon(date)) { false }

fun habitDone(h: Habit, date: String): Boolean = (h.logs[date] ?: 0) >= h.target

fun habitValue(h: Habit, date: String): Int = h.logs[date] ?: 0

/** Consecutive due days done, from today — or from yesterday while today is still open; rest days are skipped. */
fun streak(h: Habit, today: String): Int {
    var n = 0
    var d = today
    if (!habitDone(h, d)) d = addDays(d, -1)
    for (i in 0 until 400) {
        if (!habitDue(h, d)) { d = addDays(d, -1); continue }
        if (habitDone(h, d)) { n++; d = addDays(d, -1) } else break
    }
    return n
}

/** Longest run of due days done in the last 91 days (today − 90 … today). */
fun bestStreak(h: Habit, today: String): Int {
    var best = 0
    var cur = 0
    for (i in 90 downTo 0) {
        val d = addDays(today, -i)
        if (!habitDue(h, d)) continue
        if (habitDone(h, d)) { cur++; best = maxOf(best, cur) } else cur = 0
    }
    return best
}

data class Rate(val due: Int, val done: Int, val pct: Int)

/** Due / done / rounded % over the last [days] days including today. */
fun rateDays(h: Habit, days: Int, today: String): Rate {
    var due = 0
    var done = 0
    for (i in days - 1 downTo 0) {
        val d = addDays(today, -i)
        if (!habitDue(h, d)) continue
        due++
        if (habitDone(h, d)) done++
    }
    return Rate(due, done, if (due > 0) jsRound(done.toDouble() / due * 100).toInt() else 0)
}

/** % of due days done in the last 7 days. */
fun weekRate(h: Habit, today: String): Int = rateDays(h, 7, today).pct

/** Done flags for Monday..Sunday of the week containing [today]. */
fun weekDots(h: Habit, today: String): List<Boolean> {
    val start = addDays(today, -dowMon(today))
    return List(7) { habitDone(h, addDays(start, it)) }
}

fun habitsDueToday(habits: List<Habit>, today: String): List<Habit> = habits.filter { !it.archived && habitDue(it, today) }

fun habitsDoneToday(habits: List<Habit>, today: String): List<Habit> = habitsDueToday(habits, today).filter { habitDone(it, today) }

/** Rounded average [weekRate] of the non-archived habits; 0 when there are none. */
fun habitsWeekRate(habits: List<Habit>, today: String): Int {
    val hs = habits.filter { !it.archived }
    return if (hs.isEmpty()) 0 else jsRound(hs.sumOf { weekRate(it, today) }.toDouble() / hs.size).toInt()
}

/** `Math.max(0, ...habits.map(streak))` over the non-archived habits. */
fun longestStreak(habits: List<Habit>, today: String): Int = habits.filter { !it.archived }.maxOfOrNull { streak(it, today) }?.coerceAtLeast(0) ?: 0

/** Binary-habit schedule (HRow): "daily" · "Mon–Fri" (Mon–Fri on and Saturday off) · "N× a week". */
fun scheduleLabel(h: Habit): String = when {
    h.days.all { it } -> "daily"
    h.days.take(5).all { it } && !h.days[5] -> "Mon–Fri"
    else -> "${h.days.count { it }}× a week"
}

/** Measurable-habit and detail-eyebrow schedule: "daily" · "N× a week". */
fun cadenceLabel(h: Habit): String = if (h.days.all { it }) "daily" else "${h.days.count { it }}× a week"

data class HabitSubline(val text: String, val recovering: Boolean)

/** HRow's second line: `"5/8 glasses · 8 glasses · daily · 🔥 12-day streak"` or `"… · Recovering — start again today"`. */
fun habitSubline(h: Habit, today: String): HabitSubline {
    val measurable = h.type != HabitType.Binary
    val n = streak(h, today)
    val prefix = if (measurable) "${habitValue(h, today)}/${h.target} ${h.unit} · " else ""
    val schedule = if (measurable) "${h.target} ${h.unit} · ${cadenceLabel(h)}" else scheduleLabel(h)
    val tail = if (n > 0) "🔥 $n-day streak" else "Recovering — start again today"
    return HabitSubline("$prefix$schedule · $tail", recovering = n == 0)
}

/** One tap's increment for a measurable habit: count → 1, otherwise half the target (at least 1). */
fun stepFor(h: Habit): Int = if (h.type == HabitType.Count) 1 else maxOf(1, jsRound(h.target / 2.0).toInt())
