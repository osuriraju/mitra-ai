package tech.beyondscale.mitra.domain.search

import tech.beyondscale.mitra.domain.goals.goal
import tech.beyondscale.mitra.domain.habits.habit
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.money.tx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalSearchTest {
    private val tasks = listOf(
        Task("t1", "Call the plumber"), Task("t2", "Plan trip", done = true), Task("t3", "plan budget"),
        Task("t4", "Explain plans"), Task("t5", "Plank"), Task("t6", "Plants"),
    )
    private val notes = listOf(
        Note("n1", "Groceries", body = "milk, plan meals"), Note("n2", "Planning notes"), Note("n3", "Misc", tags = listOf("planner")),
    )
    private val txs = listOf(tx("x1", 100, date = "2026-10-01", merchant = "Planet Cafe"), tx("x2", 200, date = "2026-10-10", merchant = "Planet Cafe"))
    private val goals = listOf(goal(id = "g1", name = "Exit plan"))
    private val habits = listOf(habit(id = "h1", name = "Plan tomorrow"))

    private fun search(q: String, modules: Modules = Modules()) = globalSearch(q, tasks, notes, txs, goals, habits, modules)

    @Test fun rankPrefixThenWordStartThenSubstring() {
        assertEquals(0, matchRank("Plan trip", "plan"))
        assertEquals(1, matchRank("Exit plan", "plan"))
        assertEquals(2, matchRank("Airplane", "plan"))
        assertEquals(null, matchRank("Call", "plan"))
    }

    @Test fun tasksAreRankedOpenFirstAndCapped() {
        val r = search("  PLAN ")
        // Prefix hits (open ones first: t3, t5, t6, then the done t2) fill the cap of 4 before the word-start hit t4.
        assertEquals(listOf("t3", "t5", "t6", "t2"), r.tasks.map { it.id })
    }

    @Test fun notesMatchTitleBeforeBodyAndTags() {
        assertEquals(listOf("n2", "n1", "n3"), search("plan").notes.map { it.id })
    }

    @Test fun moneyIsNewestFirstAndOnlyWithTheModuleOn() {
        assertEquals(listOf("x2", "x1"), search("planet").transactions.map { it.id })
        assertTrue(search("planet", Modules(money = false)).transactions.isEmpty())
    }

    @Test fun goalsHabitsAndPlacesMatch() {
        val r = search("plan")
        assertEquals(listOf("g1"), r.goals.map { it.id })
        assertEquals(listOf("h1"), r.habits.map { it.id })
        assertEquals(listOf(SearchPlace.Budgets), search("budg").places)
        assertEquals(0, search("zzz").records)
    }

    @Test fun blankListsEnabledPlacesOnly() {
        val r = search(" ", Modules(money = false, ai = false))
        assertEquals(0, r.records)
        assertTrue(SearchPlace.Today in r.places && SearchPlace.Wellness in r.places)
        assertTrue(r.places.none { it.module == "money" || it.module == "ai" })
    }
}
