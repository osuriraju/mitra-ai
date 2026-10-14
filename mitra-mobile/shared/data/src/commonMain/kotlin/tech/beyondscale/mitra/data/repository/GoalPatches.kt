package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.MilestoneChange

/*
 * PATCH bodies for goals and milestones, and the server's input normalisation (zod `.trim()` on name, nextAction and
 * milestone title) applied before the local write, so the local copy equals the echo.
 */

/** [GoalChange]s as the server will store them. */
fun List<GoalChange>.normalized(): List<GoalChange> = map {
    when (it) {
        is GoalChange.Name -> GoalChange.Name(it.v.trim())
        is GoalChange.NextAction -> GoalChange.NextAction(it.v.trim())
        else -> it
    }
}

/** [MilestoneChange]s as the server will store them. */
fun List<MilestoneChange>.normalizedMilestone(): List<MilestoneChange> = map {
    if (it is MilestoneChange.Title) MilestoneChange.Title(it.v.trim()) else it
}

/** `PATCH goals/:id` body: only the changed keys; `Formula(null)` clears the formula (`"formula": null`). */
fun goalPatch(changes: List<GoalChange>): JsonObject = buildJsonObject {
    changes.normalized().forEach { c ->
        when (c) {
            is GoalChange.Name -> put("name", c.v)
            is GoalChange.Type -> put("type", c.v.wire)
            is GoalChange.Term -> put("term", c.v.wire)
            is GoalChange.Due -> put("due", c.v)
            is GoalChange.Current -> put("current", c.v)
            is GoalChange.Target -> put("target", c.v)
            is GoalChange.Unit -> put("unit", c.v)
            is GoalChange.Formula -> put("formula", c.v)
            is GoalChange.NextAction -> put("nextAction", c.v)
            is GoalChange.ReviewEvery -> put("reviewEvery", c.v.wire)
            is GoalChange.ReviewOn -> put("reviewOn", c.v)
            is GoalChange.State -> put("status", c.v.wire)
            is GoalChange.Notes -> put("notes", c.v)
        }
    }
}

/** `PATCH goals/:id/milestones/:mid` body: only the changed keys; cleared fields are sent as JSON `null`. */
fun milestonePatch(changes: List<MilestoneChange>): JsonObject = buildJsonObject {
    changes.normalizedMilestone().forEach { c ->
        when (c) {
            is MilestoneChange.Title -> put("title", c.v)
            is MilestoneChange.Target -> put("target", c.v)
            is MilestoneChange.TargetDate -> put("targetDate", c.v)
            is MilestoneChange.ReachedOn -> put("reachedOn", c.v)
            is MilestoneChange.Notes -> put("notes", c.v)
        }
    }
}
