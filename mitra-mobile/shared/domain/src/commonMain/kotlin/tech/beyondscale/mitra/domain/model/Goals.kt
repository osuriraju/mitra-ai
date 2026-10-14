package tech.beyondscale.mitra.domain.model

/*
 * Goal models — wire values as mitra-backend/src/goals/schemas.ts. Goal numbers are plain doubles (a ₹ goal's
 * numbers are whole rupees as typed), never paise.
 */

enum class GoalType(val wire: String, val label: String) {
    Numeric("numeric", "Numeric"),
    Milestone("milestone", "Milestone"),
    Habit("habit", "Habit-supported"),
    Date("date", "Date-based"),
    ;

    companion object { fun fromWire(s: String?): GoalType = entries.firstOrNull { it.wire == s } ?: Numeric }
}

enum class GoalTerm(val wire: String) {
    Short("Short-term"),
    Medium("Medium-term"),
    Long("Long-term"),
    ;

    companion object { fun fromWire(s: String?): GoalTerm = entries.firstOrNull { it.wire == s } ?: Short }
}

enum class ReviewEvery(val wire: String, val days: Int) {
    Weekly("Weekly", 7),
    Monthly("Monthly", 30),
    Quarterly("Quarterly", 90),
    ;

    companion object { fun fromWire(s: String?): ReviewEvery = entries.firstOrNull { it.wire == s } ?: Weekly }
}

/** The stored goal status (`status` on the wire); the derived On track / Slipping… is `GoalStatus`. */
enum class GoalState(val wire: String) {
    Active("active"),
    Paused("paused"),
    Completed("completed"),
    ;

    companion object { fun fromWire(s: String?): GoalState = entries.firstOrNull { it.wire == s } ?: Active }
}

data class Milestone(
    val id: String,
    val title: String,
    val target: Double? = null,
    val targetDate: String? = null,
    val reachedOn: String? = null,
    val notes: String? = null,
)

data class ProgressEntry(val date: String, val value: Double, val note: String? = null)

data class Goal(
    val id: String,
    val name: String,
    val type: GoalType,
    val term: GoalTerm,
    val due: String,
    val startedOn: String,
    val current: Double,
    val target: Double,
    /** "₹" or "". */
    val unit: String,
    val formula: String?,
    val nextAction: String,
    val reviewEvery: ReviewEvery,
    val reviewOn: String,
    val state: GoalState,
    val notes: String,
    val createdAt: String,
    val milestones: List<Milestone>,
    val progressLog: List<ProgressEntry>,
)

/** What the goal form produces on create; milestone targets and dates are spread by `GoalMath.newGoal`. */
data class GoalDraft(
    val name: String,
    val type: GoalType = GoalType.Numeric,
    val term: GoalTerm = GoalTerm.Medium,
    val due: String,
    val current: Double = 0.0,
    val target: Double = 100.0,
    val unit: String = "",
    val nextAction: String = "",
    val reviewEvery: ReviewEvery = ReviewEvery.Monthly,
    val milestoneTitles: List<String> = emptyList(),
)

sealed interface GoalChange {
    data class Name(val v: String) : GoalChange
    data class Type(val v: GoalType) : GoalChange
    data class Term(val v: GoalTerm) : GoalChange
    data class Due(val v: String) : GoalChange
    data class Current(val v: Double) : GoalChange
    data class Target(val v: Double) : GoalChange
    data class Unit(val v: String) : GoalChange
    data class Formula(val v: String?) : GoalChange
    data class NextAction(val v: String) : GoalChange
    data class ReviewEvery(val v: tech.beyondscale.mitra.domain.model.ReviewEvery) : GoalChange
    data class ReviewOn(val v: String) : GoalChange
    data class State(val v: GoalState) : GoalChange
    data class Notes(val v: String) : GoalChange
}

data class MilestoneDraft(
    val title: String,
    val target: Double? = null,
    val targetDate: String? = null,
    val notes: String? = null,
)

sealed interface MilestoneChange {
    data class Title(val v: String) : MilestoneChange
    data class Target(val v: Double?) : MilestoneChange
    data class TargetDate(val v: String?) : MilestoneChange
    data class ReachedOn(val v: String?) : MilestoneChange
    data class Notes(val v: String?) : MilestoneChange
}
