package tech.beyondscale.mitra.domain.habits

import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType

/* The check-off decision of the web's HRow.tap (screens/habits.tsx), shared by the list, Today and Quick Add. */

/** Streak lengths that earn the stronger haptic. */
val STREAK_MILESTONES: Set<Int> = setOf(7, 30, 100, 365)

/**
 * What one tap does: log [value] (`null` removes the log) for [date], show [snack] (when non-null) and offer Undo
 * back to [undoValue] when [canUndo]. [reachedTarget] means this tap turned the day done; [streakMilestone] means
 * the streak after it is one of [STREAK_MILESTONES].
 */
data class HabitTap(
    val habitId: String,
    val date: String,
    val value: Int?,
    val snack: String?,
    val undoValue: Int?,
    val canUndo: Boolean,
    val reachedTarget: Boolean,
    val streakMilestone: Boolean,
)

/** The check button: done → uncheck; binary → done with Undo; measurable → `+step` up to the target. */
fun habitTap(h: Habit, today: String): HabitTap {
    if (habitDone(h, today)) {
        return HabitTap(h.id, today, null, "Unchecked · ${h.name}", undoValue = null, canUndo = false, reachedTarget = false, streakMilestone = false)
    }
    if (h.type == HabitType.Binary) {
        val n = streak(h, today)
        val snack = "${h.name} ✓" + if (n > 0) " · streak ${n + 1}" else ""
        return HabitTap(h.id, today, 1, snack, undoValue = null, canUndo = true, reachedTarget = true, streakMilestone = milestoneAfter(h, today, 1))
    }
    val nv = minOf(h.target, habitValue(h, today) + stepFor(h))
    val reached = nv >= h.target
    return HabitTap(
        h.id, today, nv, if (reached) "${h.name} ✓ · target reached" else null, undoValue = null, canUndo = false,
        reachedTarget = reached, streakMilestone = reached && milestoneAfter(h, today, nv),
    )
}

/**
 * Swipe right / "Done today": log the full target, Undo back to the previous value (`null` when there was none).
 * A day that is already done is left alone: the tap keeps the current value (an over-target 10/8 is never lowered),
 * with no snack and no Undo — callers may skip the write when `value == habitValue(h, today)`.
 */
fun habitComplete(h: Habit, today: String): HabitTap {
    val before = h.logs[today]
    if (habitDone(h, today)) {
        return HabitTap(h.id, today, before, snack = null, undoValue = before, canUndo = false, reachedTarget = false, streakMilestone = false)
    }
    return HabitTap(
        h.id, today, h.target, "${h.name} ✓", undoValue = before, canUndo = true,
        reachedTarget = true, streakMilestone = milestoneAfter(h, today, h.target),
    )
}

private fun milestoneAfter(h: Habit, today: String, value: Int): Boolean =
    streak(h.copy(logs = h.logs + (today to value)), today) in STREAK_MILESTONES
