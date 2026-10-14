package tech.beyondscale.mitra.feature.common

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.tasks.Completion
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionEventsTest {
    private val task = Task(id = "t1", title = "Call John")

    @Test fun completedEmitsHapticThenSnackWithUndo() = runTest {
        val q = EventQueue<CompletionUndo>()
        q.announce(Completion.Completed(task, "t2")) { it }
        q.events.test {
            assertEquals(UiEvent.Haptic(HapticKind.Confirm), awaitItem())
            assertEquals(UiEvent.Snack("Completed · Call John", CompletionUndo("t1", "t2")), awaitItem())
            expectNoEvents()
        }
    }

    @Test fun reopenedAndNotFoundEmitNothing() = runTest {
        val q = EventQueue<CompletionUndo>()
        q.announce(Completion.Reopened(task.copy(done = true))) { it }
        q.announce(Completion.NotFound) { it }
        q.events.test { expectNoEvents() }
    }
}
