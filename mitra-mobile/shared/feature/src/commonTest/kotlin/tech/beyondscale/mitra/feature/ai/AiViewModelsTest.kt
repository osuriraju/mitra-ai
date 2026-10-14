package tech.beyondscale.mitra.feature.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiReply
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.RescheduleMove
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionAction
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeAiRepository
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AiViewModelsTest : ViewModelTest() {
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val ai = FakeAiRepository()
    private val tasks = FakeTasksRepository(listOf(Task("t1", "Call mom", due = "2026-10-14")), today = "2026-10-14")
    private val money = FakeMoneyRepository(categories = listOf(Category("c1", "Food", "🍔", CategoryKind.Expense, budgetPaise = 500_000)))
    private val goals = FakeGoalsRepository(clock = clock)
    private val habits = FakeHabitsRepository()
    private val runner = AiActionRunner(tasks, money, goals, habits, clock)

    private fun <A> TestScope.collect(state: kotlinx.coroutines.flow.Flow<*>, events: kotlinx.coroutines.flow.Flow<UiEvent<A>>): List<UiEvent<A>> {
        val out = mutableListOf<UiEvent<A>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { state.collect {} }
        backgroundScope.launch(d) { events.toList(out) }
        runCurrent()
        return out
    }

    /* ---------- chat ---------- */

    @Test fun replyIsRevealedProgressively() = runTest {
        val vm = AiChatViewModel(ai, runner)
        collect(vm.state, vm.events)
        ai.nextReply = AiReply("x".repeat(300), chart = listOf(1.0, 2.0))
        vm.onAction(AiChatAction.Send("  How was my week?  "))
        assertEquals("ask:How was my week?", ai.calls.last { it.startsWith("ask") })
        val reply = vm.state.value.messages.last()
        assertTrue(reply.revealing)
        assertTrue(reply.text.length < 300)
        assertNull(reply.chart) // shown once the text is complete
        advanceTimeBy(AiChatViewModel.REVEAL_TICK_MS * 3 + 1)
        assertTrue(vm.state.value.messages.last().text.length in 1 until 300)
        advanceUntilIdle()
        val done = vm.state.value.messages.last()
        assertFalse(done.revealing)
        assertEquals(300, done.text.length)
        assertEquals(listOf(1.0, 2.0), done.chart)
        assertTrue(done.showSources)
    }

    @Test fun offlineAskShowsOfflineState() = runTest {
        val vm = AiChatViewModel(ai, runner)
        collect(vm.state, vm.events)
        ai.askError = AppError.Offline
        vm.onAction(AiChatAction.Send("Hi"))
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.typing)
        assertEquals("Sorry — You appear to be offline", vm.state.value.messages.last().text)
        ai.askError = null
        vm.onAction(AiChatAction.Send("Hi again"))
        advanceUntilIdle()
        assertFalse(vm.state.value.offline)
    }

    @Test fun proposalAppliesOnlyOnConfirmAndUndoReverts() = runTest {
        val vm = AiChatViewModel(ai, runner)
        val events = collect(vm.state, vm.events)
        ai.nextReply = AiReply("Add it?", proposal = AiProposal.Task("Call John", "Tomorrow", "Call John", due = "2026-10-15"))
        vm.onAction(AiChatAction.Send("Remind me to call John"))
        advanceUntilIdle()
        val msg = vm.state.value.messages.last()
        assertTrue(tasks.calls.none { it.startsWith("create") }) // preview only

        vm.onAction(AiChatAction.Confirm(msg.id))
        vm.onAction(AiChatAction.Confirm(msg.id)) // a double tap applies once
        assertEquals(listOf("create:Call John"), tasks.calls.filter { it.startsWith("create") })
        val logged = ai.actionFlow.value.single()
        assertEquals("Created task", logged.action)
        assertEquals(listOf(RevertOp(RevertOp.DELETE_TASK, "new1")), logged.revert)
        assertTrue(vm.state.value.messages.last().applied)
        val snack = events.filterIsInstance<UiEvent.Snack<AiChatAction>>().last()
        assertEquals("Task added", snack.text)

        vm.onAction(snack.undo!!)
        assertTrue("delete:new1" in tasks.calls)
        assertFalse(vm.state.value.messages.last().applied)
        assertTrue(ai.actionFlow.value.first { it.id == logged.id }.reverted)
    }

    @Test fun cancelDropsProposal() = runTest {
        val vm = AiChatViewModel(ai, runner)
        collect(vm.state, vm.events)
        ai.nextReply = AiReply("Save?", proposal = AiProposal.Expense("Dinner", "Food", 450.0, "c1", "a1", "Dinner"))
        vm.onAction(AiChatAction.Send("Spent 450 on dinner"))
        advanceUntilIdle()
        vm.onAction(AiChatAction.Cancel(vm.state.value.messages.last().id))
        assertNull(vm.state.value.messages.last().proposal)
        assertEquals("Save? (cancelled)", vm.state.value.messages.last().text)
        assertTrue(money.calls.none { it == "addTransaction" })
    }

    @Test fun rescheduleUndoRestoresPreviousDue() = runTest {
        val draft = runner.applyProposal(AiProposal.Reschedule("Keep today: x", "", listOf(RescheduleMove("t1", "Call mom", "2026-10-16", "Fri"))))
        assertEquals("2026-10-16", tasks.taskFlow.value.single().due)
        runner.runRevert(draft.revert)
        assertEquals("2026-10-14", tasks.taskFlow.value.single().due)
    }

    /* ---------- suggestions ---------- */

    private val budget = Suggestion("s1", "wallet", "Set a food budget", "You overspend", "Money", action = SuggestionAction.SetBudget("c1", 8000.0))

    @Test fun suggestionNeedsPreviewBeforeApplyAndUndoRestoresBudget() = runTest {
        ai.serverSuggestions = listOf(budget)
        val vm = AiSuggestionsViewModel(ai, runner)
        val events = collect(vm.state, vm.events)
        assertEquals(listOf("s1"), vm.state.value.list.map { it.id }) // loaded on open

        vm.onAction(AiSuggestionsAction.Apply("s1"))
        assertTrue(money.calls.none { it.startsWith("updateCategory") }) // no preview yet: ignored

        vm.onAction(AiSuggestionsAction.TogglePreview("s1"))
        vm.onAction(AiSuggestionsAction.Apply("s1"))
        assertEquals(800_000L, money.categoryFlow.value.single().budgetPaise)
        assertEquals(SuggestionState.Applied, ai.suggestionFlow.value.single().state)
        assertEquals(0, vm.state.value.openCount)
        val logged = ai.actionFlow.value.single()
        assertEquals("""{"budget":5000.0}""", logged.revert.single().patchJson)

        val snack = events.filterIsInstance<UiEvent.Snack<AiSuggestionsAction>>().last()
        vm.onAction(snack.undo!!)
        assertEquals(500_000L, money.categoryFlow.value.single().budgetPaise)
        assertEquals(SuggestionState.Open, ai.suggestionFlow.value.single().state)
    }

    @Test fun offlineSuggestionsShowOfflineState() = runTest {
        ai.suggestionsError = AppError.Offline
        val vm = AiSuggestionsViewModel(ai, runner)
        collect(vm.state, vm.events)
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.busy)
    }

    @Test fun dismissAndRestore() = runTest {
        ai.serverSuggestions = listOf(budget)
        val vm = AiSuggestionsViewModel(ai, runner)
        collect(vm.state, vm.events)
        vm.onAction(AiSuggestionsAction.Dismiss("s1"))
        assertTrue(vm.state.value.list.isEmpty())
        vm.onAction(AiSuggestionsAction.Tab(1))
        assertEquals(listOf("s1"), vm.state.value.list.map { it.id })
        vm.onAction(AiSuggestionsAction.RestoreAll)
        assertEquals(SuggestionState.Open, ai.suggestionFlow.value.single().state)
    }

    /* ---------- log ---------- */

    @Test fun logShowsLocalWhenAndRevertRunsOpsOnce() = runTest {
        ai.actionFlow.value = listOf(
            AiAction("a2", "2026-10-14T04:30:00.000Z", "Created task", "Call mom", revertible = true, revert = listOf(RevertOp(RevertOp.DELETE_TASK, "t1"))),
            AiAction("a1", "2026-08-01T04:30:00.000Z", "Rescheduled 2 tasks", "x"),
        )
        val vm = AiLogViewModel(ai, runner, clock)
        val events = collect(vm.state, vm.events)
        assertEquals(listOf("a2"), vm.state.value.rows.map { it.id }) // last 30 days
        assertEquals("Today 10:00 AM · Approved by you", vm.state.value.rows.single().sub)
        vm.onAction(AiLogAction.ToggleRange)
        assertEquals(2, vm.state.value.rows.size)
        vm.onAction(AiLogAction.NextType)
        assertEquals("Created", vm.state.value.type)

        vm.onAction(AiLogAction.Revert("a2"))
        vm.onAction(AiLogAction.Revert("a2"))
        assertEquals(1, tasks.calls.count { it == "delete:t1" })
        assertEquals(1, vm.state.value.revertedCount)
        assertEquals("Reverted · original entry kept", (events.last() as UiEvent.Snack).text)
    }
}
