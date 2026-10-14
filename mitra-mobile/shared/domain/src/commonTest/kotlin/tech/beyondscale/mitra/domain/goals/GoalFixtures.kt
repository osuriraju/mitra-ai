package tech.beyondscale.mitra.domain.goals

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery

/** Wednesday — every test's "today". */
internal const val TODAY = "2026-10-14"

internal fun d(n: Int) = addDays(TODAY, n)

internal fun goal(
    id: String = "g",
    name: String = "Goal",
    type: GoalType = GoalType.Numeric,
    current: Double = 0.0,
    target: Double = 100.0,
    unit: String = "",
    startedOn: String = "2026-07-16",
    due: String = "2027-01-12",
    state: GoalState = GoalState.Active,
    reviewOn: String = d(14),
    milestones: List<Milestone> = emptyList(),
    progressLog: List<ProgressEntry> = emptyList(),
    formula: String? = null,
) = Goal(
    id, name, type, GoalTerm.Medium, due, startedOn, current, target, unit, formula, nextAction = "", reviewEvery = ReviewEvery.Monthly,
    reviewOn = reviewOn, state = state, notes = "", createdAt = "2026-01-05T00:00:00.000Z", milestones = milestones, progressLog = progressLog,
)

/* The web seed's goals (mitra-frontend/src/store/seed.ts), with reviewOn relative to TODAY like `d(n)` there. */
internal val emergencyFund = Goal(
    "g-fund", "Build ₹1,00,000 emergency fund", GoalType.Numeric, GoalTerm.Medium, "2027-03-31", "2026-01-05", 62000.0, 100000.0, "₹",
    "SBI Savings balance tagged “Emergency” (₹54,000) + recurring transfers scheduled (₹8,000)", "Transfer ₹8,000 on 1 Oct", ReviewEvery.Monthly, d(14),
    GoalState.Active, "", "2026-01-05T00:00:00.000Z",
    listOf(
        Milestone("m1", "₹25,000", 25000.0, reachedOn = "2026-03-20"),
        Milestone("m2", "₹50,000", 50000.0, reachedOn = "2026-07-08"),
        Milestone("m3", "₹75,000", 75000.0, targetDate = "2026-11-30", notes = "If the bonus lands, this milestone can close a month early."),
        Milestone("m4", "₹1,00,000", 100000.0, targetDate = "2027-03-31"),
    ),
    listOf(8, 15, 25, 31, 38, 44, 50, 54, 62).mapIndexed { i, v -> ProgressEntry("2026-${(i + 1).toString().padStart(2, '0')}-01", v * 1000.0) },
)

internal val run10k = Goal(
    "g-10k", "Run a 10K", GoalType.Milestone, GoalTerm.Short, "2026-12-15", "2026-06-01", 2.0, 5.0, "", null, "5K run on Saturday",
    ReviewEvery.Monthly, d(-1), GoalState.Active, "", "2026-01-05T00:00:00.000Z",
    listOf(
        Milestone("m5", "Walk 30 min daily for 4 weeks", reachedOn = "2026-07-01"),
        Milestone("m6", "Run 2K without stopping", reachedOn = "2026-08-10"),
        Milestone("m7", "Run 5K", targetDate = d(3)),
        Milestone("m8", "Run 8K", targetDate = "2026-11-15"),
        Milestone("m9", "Race day 10K", targetDate = "2026-12-15"),
    ),
    listOf(ProgressEntry("2026-07-01", 1.0), ProgressEntry("2026-08-10", 2.0)),
)

internal val books = Goal(
    "g-books", "Read 24 books this year", GoalType.Numeric, GoalTerm.Long, "2026-12-31", "2026-01-01", 14.0, 24.0, "", null, "Finish Atomic Habits",
    ReviewEvery.Monthly, d(20), GoalState.Active, "", "2026-01-05T00:00:00.000Z",
    listOf(
        Milestone("m16", "6 books", 6.0, reachedOn = "2026-03-30"),
        Milestone("m17", "12 books", 12.0, reachedOn = "2026-07-20"),
        Milestone("m18", "18 books", 18.0, targetDate = "2026-10-15"),
        Milestone("m19", "24 books", 24.0, targetDate = "2026-12-31"),
    ),
    listOf(2, 4, 6, 8, 9, 11, 12, 13, 14).mapIndexed { i, v -> ProgressEntry("2026-${(i + 1).toString().padStart(2, '0')}-28", v.toDouble()) },
)

internal val spanish = Goal(
    "g-spanish", "Learn Spanish to B1", GoalType.Habit, GoalTerm.Long, "2027-06-30", "2026-04-01", 22.0, 100.0, "", null, "Complete Unit 4",
    ReviewEvery.Monthly, d(-6), GoalState.Active, "", "2026-01-05T00:00:00.000Z",
    listOf(
        Milestone("m20", "A1 — units 1–6", reachedOn = "2026-06-30"),
        Milestone("m21", "A2 — units 7–14", targetDate = "2026-12-31"),
        Milestone("m22", "B1 — conversation practice", targetDate = "2027-06-30"),
    ),
    listOf(ProgressEntry("2026-05-01", 8.0), ProgressEntry("2026-06-30", 18.0), ProgressEntry("2026-08-01", 22.0)),
)
