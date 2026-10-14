package tech.beyondscale.mitra.domain.goals

import tech.beyondscale.mitra.core.MON
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.domain.habits.rateDays
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import kotlin.math.max
import kotlin.math.min

/*
 * Ports of the goals block of mitra-frontend/src/store/selectors.ts and the label helpers of screens/goals.tsx.
 * Every function takes the device's local `today` explicitly.
 */

/** 0..100: milestones reached ÷ total · time elapsed · average 30-day rate of linked habits · current ÷ target. */
fun goalProgress(g: Goal, habits: List<Habit>, today: String): Int {
    when (g.type) {
        GoalType.Milestone -> return if (g.milestones.isEmpty()) 0 else
            jsRound(g.milestones.count { it.reachedOn != null }.toDouble() / g.milestones.size * 100).toInt()
        GoalType.Date -> {
            val total = max(1, diffDays(g.due, g.startedOn))
            return min(100, jsRound(diffDays(today, g.startedOn).toDouble() / total * 100).toInt())
        }
        GoalType.Habit -> {
            val linked = habits.filter { it.goalId == g.id }
            if (linked.isNotEmpty()) return jsRound(linked.sumOf { rateDays(it, 30, today).pct }.toDouble() / linked.size).toInt()
        }
        GoalType.Numeric -> Unit
    }
    return min(100L, jsRound(g.current / max(1.0, g.target) * 100)).toInt()
}

enum class GoalStatus(val label: String) {
    OnTrack("On track"),
    Attention("Needs attention"),
    Behind("Behind"),
    Slipping("Slipping"),
    Completed("Completed"),
    Paused("Paused"),
}

/** Progress against the straight line from startedOn to due; stale (no entry in 21+ days) and 10+ behind = slipping. */
fun goalStatus(g: Goal, habits: List<Habit>, today: String): GoalStatus {
    if (g.state == GoalState.Completed) return GoalStatus.Completed
    if (g.state == GoalState.Paused) return GoalStatus.Paused
    val p = goalProgress(g, habits, today)
    val total = max(1, diffDays(g.due, g.startedOn))
    val expected = min(100.0, max(0.0, diffDays(today, g.startedOn).toDouble() / total * 100))
    val lastLog = g.progressLog.lastOrNull()?.date
    val stale = lastLog == null || diffDays(today, lastLog) > 21
    return when {
        stale && p < expected - 10 -> GoalStatus.Slipping
        p >= expected - 5 -> GoalStatus.OnTrack
        p >= expected - 15 -> GoalStatus.Attention
        else -> GoalStatus.Behind
    }
}

enum class StatusTone { Success, Warning, Danger, Accent, Neutral }

/** `STATUS_TONE` — the status chip. */
fun statusTone(s: GoalStatus): StatusTone = when (s) {
    GoalStatus.OnTrack -> StatusTone.Success
    GoalStatus.Attention, GoalStatus.Behind -> StatusTone.Warning
    GoalStatus.Slipping -> StatusTone.Danger
    GoalStatus.Completed -> StatusTone.Accent
    GoalStatus.Paused -> StatusTone.Neutral
}

/** Today card / goal review ring: on track → accent, slipping → danger, anything else → warning. */
fun ringTone(s: GoalStatus): StatusTone = when (s) {
    GoalStatus.OnTrack -> StatusTone.Accent
    GoalStatus.Slipping -> StatusTone.Danger
    else -> StatusTone.Warning
}

/** GoalCard ring: completed → success, on track → accent, slipping → danger, anything else → warning. */
fun cardRingTone(s: GoalStatus): StatusTone = when (s) {
    GoalStatus.Completed -> StatusTone.Success
    GoalStatus.OnTrack -> StatusTone.Accent
    GoalStatus.Slipping -> StatusTone.Danger
    else -> StatusTone.Warning
}

/** Goal detail progress bar: slipping → danger, on track / completed → accent, anything else → warning. */
fun barTone(s: GoalStatus): StatusTone = when (s) {
    GoalStatus.Slipping -> StatusTone.Danger
    GoalStatus.OnTrack, GoalStatus.Completed -> StatusTone.Accent
    else -> StatusTone.Warning
}

private fun goalNumber(g: Goal, x: Double): String = if (g.unit == "₹") "₹" + localeIN(x) else jsNum(x)

/** "₹62,000" · "14" · "12.5" */
fun goalValueLabel(g: Goal): String = goalNumber(g, g.current)

/** "₹1,00,000" · "24" */
fun goalTargetLabel(g: Goal): String = goalNumber(g, g.target)

/** Active goals whose review date is today or earlier. */
fun reviewDue(g: Goal, today: String): Boolean = g.state == GoalState.Active && g.reviewOn <= today

/** "past due" · "30 days left" · "2 months left" (web `monthsLeft`, copy as is). */
fun monthsLeft(due: String, today: String): String {
    val d = diffDays(due, today)
    return when {
        d < 0 -> "past due"
        d < 31 -> "$d days left"
        else -> "${jsRound(d / 30.0)} months left"
    }
}

/** "Mar 2027" — GoalCard's `due …`. */
fun dueMonthLabel(g: Goal): String = "${MON[g.due.substring(5, 7).toInt() - 1]} ${g.due.take(4)}"

/** "1 project · 3 tasks · 2 habits" or "Nothing linked yet" (GoalCard). */
fun linksLine(goalId: String, tasks: List<Task>, habits: List<Habit>, projects: List<Project>): String {
    fun part(n: Int, word: String) = if (n == 0) null else "$n $word${if (n > 1) "s" else ""}"
    return listOfNotNull(
        part(projects.count { it.goalId == goalId }, "project"),
        part(tasks.count { it.goalId == goalId }, "task"),
        part(habits.count { it.goalId == goalId }, "habit"),
    ).joinToString(" · ").ifEmpty { "Nothing linked yet" }
}

/** "How this is calculated": the goal's own formula, else the web default for its type. */
fun formulaText(g: Goal): String = g.formula?.takeIf { it.isNotEmpty() } ?: when (g.type) {
    GoalType.Milestone -> "milestones reached ÷ total milestones"
    GoalType.Habit -> "average 30-day consistency of linked habits"
    GoalType.Date -> "time elapsed ÷ total time to due date"
    GoalType.Numeric -> "${goalValueLabel(g)} ÷ ${goalTargetLabel(g)}"
}

/** Goal detail "Blockers" copy; empty when there is nothing to show. */
fun blockers(g: Goal, status: GoalStatus): String = when {
    status == GoalStatus.Slipping -> "No progress logged in 3+ weeks — the goal is drifting from its target line."
    status == GoalStatus.Behind -> "Progress is below the pace needed to finish by the due date."
    g.unit == "₹" && status != GoalStatus.OnTrack -> "Over-budget categories this month reduce the surplus available to transfer."
    else -> ""
}
