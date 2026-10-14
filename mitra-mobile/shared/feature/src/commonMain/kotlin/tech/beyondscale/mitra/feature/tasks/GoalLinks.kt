package tech.beyondscale.mitra.feature.tasks

import tech.beyondscale.mitra.domain.model.Goal

/** A goal a task or project links to; [due] is the goal's ISO due date. */
data class GoalChip(val id: String, val name: String, val due: String)

/** The linked goal, or null when the id is unset or unknown (a deleted goal's id is kept on the server — no FK). */
internal fun goalChip(goals: List<Goal>, goalId: String?): GoalChip? =
    goalId?.let { id -> goals.firstOrNull { it.id == id } }?.let { GoalChip(it.id, it.name, it.due) }

/** Projects list card label (web `ProjectsScreen`): a leading "Build " / "Ship " dropped, at most 22 characters. */
internal fun shortGoalName(name: String): String = name.replaceFirst(LEADING_VERB, "").take(22)

private val LEADING_VERB = Regex("^Build |^Ship ")
