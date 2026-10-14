package tech.beyondscale.mitra.feature.common

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.tasks.Completion
import tech.beyondscale.mitra.testing.FakeTasksRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompletionEventsTest {
    private val task = Task(id = "t1", title = "Call John")

    @Test fun completedEmitsHapticThenSnackWithUndo() = runTest {
        val repo = FakeTasksRepository(listOf(task)).apply { spawnOnComplete = "t2" }
        val q = EventQueue<CompletionUndo>()
        assertEquals(Completion.Completed(task, "t2"), q.toggleTask(repo, "t1") { it })
        q.events.test {
            assertEquals(UiEvent.Haptic(HapticKind.Confirm), awaitItem())
            assertEquals(UiEvent.Snack("Completed · Call John", CompletionUndo("t1", "t2")), awaitItem())
            expectNoEvents()
        }
    }

    /** Instant tap feedback: the haptic is sent before the write, so a failing write still produced it. */
    @Test fun hapticFiresBeforeTheWrite() = runTest {
        val repo = FakeTasksRepository(listOf(task)).apply { setDoneError = IllegalStateException("disk") }
        val q = EventQueue<CompletionUndo>()
        assertFailsWith<IllegalStateException> { q.toggleTask(repo, "t1") { it } }
        q.events.test {
            assertEquals(UiEvent.Haptic(HapticKind.Confirm), awaitItem())
            expectNoEvents()
        }
    }

    @Test fun reopenAndUnknownEmitNothing() = runTest {
        val repo = FakeTasksRepository(listOf(task.copy(done = true, status = TaskStatus.Done)))
        val q = EventQueue<CompletionUndo>()
        q.toggleTask(repo, "t1") { it }
        q.toggleTask(repo, "nope") { it }
        q.events.test { expectNoEvents() }
        assertEquals(listOf("done:t1:false"), repo.calls)
    }

    @Test fun announceSendsOnlyTheSnack() = runTest {
        val q = EventQueue<CompletionUndo>()
        q.announce(Completion.Completed(task, null)) { it }
        q.announce(Completion.Reopened(task)) { it }
        q.announce(Completion.NotFound) { it }
        q.events.test {
            assertEquals(UiEvent.Snack("Completed · Call John", CompletionUndo("t1", null)), awaitItem())
            expectNoEvents()
        }
    }
}
