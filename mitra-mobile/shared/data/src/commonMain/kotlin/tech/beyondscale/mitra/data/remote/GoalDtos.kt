package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery

/*
 * Shapes of mitra-backend/src/goals/goals.service.ts `goalOut` / `milestoneOut` (`undefined` fields are simply absent).
 * Goal numbers are plain doubles on both sides (whole rupees for a ₹ goal, never paise). The server output is already
 * derived (milestone-type current/target), so `toDomain` takes it as is.
 */

@Serializable
data class MilestoneDto(
    val id: String,
    val title: String,
    val target: Double? = null,
    val targetDate: String? = null,
    val reachedOn: String? = null,
    val notes: String? = null,
) {
    fun toDomain() = Milestone(id, title, target, targetDate, reachedOn, notes)
}

@Serializable
data class ProgressDto(val date: String, val value: Double, val note: String? = null) {
    fun toDomain() = ProgressEntry(date, value, note)
}

@Serializable
data class GoalDto(
    val id: String,
    val name: String,
    val type: String = "numeric",
    val term: String = "Medium-term",
    val due: String,
    val startedOn: String,
    val current: Double = 0.0,
    val target: Double = 100.0,
    val unit: String = "",
    val formula: String? = null,
    val nextAction: String = "",
    val reviewEvery: String = "Monthly",
    val reviewOn: String,
    val status: String = "active",
    val notes: String = "",
    val createdAt: String = "",
    val milestones: List<MilestoneDto> = emptyList(),
    val progressLog: List<ProgressDto> = emptyList(),
) {
    fun toDomain() = Goal(
        id = id, name = name, type = GoalType.fromWire(type), term = GoalTerm.fromWire(term), due = due, startedOn = startedOn,
        current = current, target = target, unit = unit, formula = formula, nextAction = nextAction,
        reviewEvery = ReviewEvery.fromWire(reviewEvery), reviewOn = reviewOn, state = GoalState.fromWire(status), notes = notes,
        createdAt = createdAt, milestones = milestones.map { it.toDomain() }, progressLog = progressLog.map { it.toDomain() },
    )
}

/** `POST goals` body, as the web `addGoal` (`nulls(...)`: absent optionals are sent as `null`); milestones keep their ids. */
fun Goal.createBody(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("type", type.wire)
    put("term", term.wire)
    put("due", due)
    put("startedOn", startedOn)
    put("current", current)
    put("target", target)
    put("unit", unit)
    put("formula", formula)
    put("nextAction", nextAction)
    put("reviewEvery", reviewEvery.wire)
    put("reviewOn", reviewOn)
    put("status", state.wire)
    put("notes", notes)
    putJsonArray("milestones") { milestones.forEach { add(milestoneBody(it)) } }
}

/** `POST goals/:id/milestones` body (and one entry of the create body). */
fun milestoneBody(m: Milestone): JsonObject = buildJsonObject {
    put("id", m.id)
    put("title", m.title)
    put("target", m.target)
    put("targetDate", m.targetDate)
    put("reachedOn", m.reachedOn)
    put("notes", m.notes)
}
