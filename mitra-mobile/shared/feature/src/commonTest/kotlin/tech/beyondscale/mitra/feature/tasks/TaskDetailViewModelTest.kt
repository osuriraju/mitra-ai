package tech.beyondscale.mitra.feature.tasks

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.ApplicationScope
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.SubProgress
import tech.beyondscale.mitra.domain.tasks.nextMonday
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
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
class TaskDetailViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Tue 13 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val project = Project("p1", "Mitra", "🚀")
    private val task = Task(
        "t1", "Write report", projectId = "p1", due = "2026-10-13", time = "17:00", estMin = 45, pri = Priority.High,
        recurring = "Weekly", notes = "Draft", createdAt = "2026-10-13T03:00:00Z",
    )
    private val child = Task("c1", "Outline", parentId = "t1", done = true, status = TaskStatus.Done, completedAt = "2026-10-13")
    private val child2 = Task("c2", "Charts", parentId = "t1")

    private class Harness(val vm: TaskDetailViewModel, val events: List<UiEvent<TaskDetailAction>>)

    /** Runs app-scope work (the last-moment notes flush) immediately. */
    private fun TestScope.appScope() = ApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    private fun TestScope.start(repo: TasksRepository, id: String = "t1", vm: TaskDetailViewModel = TaskDetailViewModel(id, repo, clock, appScope())): Harness {
        val events = mutableListOf<UiEvent<TaskDetailAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun showsLinesLikeWeb() = runTest {
        val repo = FakeTasksRepository(listOf(task, child, child2), listOf(project))
        val s = start(repo).vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.notFound)
        assertEquals("Write report", s.title)
        assertEquals("Today · 5:00 PM", s.dueLine)
        assertFalse(s.overdue)
        assertEquals("45m · effort: medium", s.estimateLine)
        assertEquals("Weekly", s.repeatLine)
        assertEquals("Created Today by you", s.createdLine)
        assertEquals("High priority" to ChipTone.Danger, s.priChip)
        assertEquals(project, s.project)
        assertEquals(SubProgress(1, 2), s.subProgress)
        assertEquals(listOf("c1", "c2"), s.subtasks.map { it.id })
        assertEquals("Draft", s.notes)
        assertNull(s.parent)

        val c = start(repo, "c1").vm.state.value
        assertEquals(ParentUi("t1", "Write report", "1/2"), c.parent)
    }

    @Test fun linesForBareTask() = runTest {
        val bare = Task("t2", "Call", due = "2026-10-10", estMin = 120, source = "ai", createdAt = "2026-10-12T20:00:00Z",
            done = true, status = TaskStatus.Done, completedAt = "2026-10-13")
        val s = start(FakeTasksRepository(listOf(bare)), "t2").vm.state.value
        assertEquals("Sat 10 Oct", s.dueLine)
        assertEquals("2h · effort: high", s.estimateLine)
        assertEquals("Does not repeat", s.repeatLine)
        // 20:00Z on the 12th is already the 13th in India.
        assertEquals("Created Today by Mitra (approved by you) · completed Today", s.createdLine)
        assertTrue(s.ai)
        assertNull(s.priChip)
    }

    @Test fun statusDoneCompletes() = runTest {
        val repo = FakeTasksRepository(listOf(task))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.SetStatus(TaskStatus.Done))
        runCurrent()
        assertEquals(listOf("update:t1:Status(value=Done)"), repo.calls)
        assertTrue(h.vm.state.value.done)
    }

    @Test fun toggleDoneCompletesWithUndo() = runTest {
        val repo = FakeTasksRepository(listOf(task)).apply { spawnOnComplete = "next1" }
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.ToggleDone)
        runCurrent()
        val undo = TaskDetailAction.UndoComplete("t1", "next1")
        assertEquals(listOf(UiEvent.Haptic(HapticKind.Confirm), UiEvent.Snack("Completed · Write report", undo)), h.events)
        h.vm.onAction(undo)
        runCurrent()
        assertEquals(listOf("done:t1:true", "done:t1:false", "delete:next1"), repo.calls)
    }

    @Test fun rescheduleNextMonday() = runTest {
        val repo = FakeTasksRepository(listOf(task))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.Reschedule(nextMonday(h.vm.state.value.today), "Next Monday"))
        runCurrent()
        assertEquals(listOf("update:t1:Due(value=2026-10-19)"), repo.calls)
        assertEquals(listOf(UiEvent.Snack<TaskDetailAction>("Rescheduled · Next Monday")), h.events)
    }

    @Test fun notesAreDebounced() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeTasksRepository(listOf(task))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.Notes("a"))
        advanceTimeBy(200)
        h.vm.onAction(TaskDetailAction.Notes("ab"))
        advanceTimeBy(200)
        h.vm.onAction(TaskDetailAction.Notes("abc"))
        runCurrent()
        assertEquals("abc", h.vm.state.value.notes)
        advanceTimeBy(599)
        assertEquals(emptyList(), repo.calls)
        advanceTimeBy(2)
        assertEquals(listOf("update:t1:Notes(value=abc)"), repo.calls)
        runCurrent()
        assertEquals("abc", h.vm.state.value.notes)
    }

    @Test fun pendingNotesAreSavedWhenTheViewModelIsCleared() = runTest {
        val repo = FakeTasksRepository(listOf(task))
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { TaskDetailViewModel("t1", repo, clock, appScope()) } })[TaskDetailViewModel::class]
        start(repo, vm = vm)
        vm.onAction(TaskDetailAction.Notes("last words"))
        store.clear()
        runCurrent()
        assertEquals(listOf("update:t1:Notes(value=last words)"), repo.calls)
        advanceTimeBy(1_000)
        assertEquals(listOf("update:t1:Notes(value=last words)"), repo.calls)
    }

    @Test fun flushNowSavesOnceAndCancelsTheDebounce() = runTest {
        val repo = FakeTasksRepository(listOf(task))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.Notes("abc"))
        h.vm.flushNow()
        h.vm.flushNow()
        advanceTimeBy(1_000)
        assertEquals(listOf("update:t1:Notes(value=abc)"), repo.calls)
        h.vm.flushNow()
        assertEquals(listOf("update:t1:Notes(value=abc)"), repo.calls)
    }

    @Test fun draftStaysUntilTheStoreCatchesUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val inner = FakeTasksRepository(listOf(task))
        val repo = LaggingRepository(inner)
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.Notes("abc"))
        advanceTimeBy(601)
        runCurrent()
        assertEquals(listOf("update:t1:Notes(value=abc)"), inner.calls)
        // The write landed but the observed flow has not re-emitted: still the typed text, not the stale "Draft".
        assertEquals("abc", h.vm.state.value.notes)
        repo.catchUp()
        runCurrent()
        assertEquals("abc", h.vm.state.value.notes)
        // The draft is gone once the store matched it, so later changes from elsewhere show through.
        inner.taskFlow.value = listOf(task.copy(notes = "edited elsewhere"))
        repo.catchUp()
        runCurrent()
        assertEquals("edited elsewhere", h.vm.state.value.notes)
    }

    /** A repository whose task flow only reflects writes when [catchUp] is called (like a slow DB query re-run). */
    private class LaggingRepository(private val inner: FakeTasksRepository) : TasksRepository by inner {
        private val shown = MutableStateFlow(inner.taskFlow.value)
        override val tasks: Flow<List<Task>> = shown
        fun catchUp() { shown.value = inner.taskFlow.value }
    }

    @Test fun addSubtaskInheritsProjectAndDue() = runTest {
        val repo = FakeTasksRepository(listOf(task))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.AddSubtask("  Charts  "))
        runCurrent()
        assertEquals(listOf("create:Charts"), repo.calls)
        val sub = repo.taskFlow.value.first { it.id == "new1" }
        assertEquals("t1", sub.parentId)
        assertEquals("p1", sub.projectId)
        assertEquals("2026-10-13", sub.due)
        assertEquals(listOf(UiEvent.Snack<TaskDetailAction>("Subtask added · Charts")), h.events)
        assertEquals(listOf("new1"), h.vm.state.value.subtasks.map { it.id })

        h.vm.onAction(TaskDetailAction.AddSubtask("   "))
        runCurrent()
        assertEquals(listOf("create:Charts"), repo.calls)
    }

    @Test fun duplicateCopiesTheTask() = runTest {
        val repo = FakeTasksRepository(listOf(task.copy(done = true, status = TaskStatus.Done, completedAt = "2026-10-13")))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.Duplicate)
        runCurrent()
        assertEquals(listOf("create:Write report (copy)"), repo.calls)
        val copy = repo.taskFlow.value.first { it.id == "new1" }
        assertEquals(TaskStatus.Todo, copy.status)
        assertEquals(Priority.High, copy.pri)
        assertEquals("Weekly", copy.recurring)
        assertEquals(listOf(UiEvent.Snack<TaskDetailAction>("Duplicated")), h.events)
    }

    @Test fun deleteEmitsRemoved() = runTest {
        val repo = FakeTasksRepository(listOf(task, child))
        val h = start(repo)
        h.vm.onAction(TaskDetailAction.Delete)
        runCurrent()
        assertEquals(listOf(task, child), h.vm.deleted.first())
        assertEquals(listOf("delete:t1"), repo.calls)
    }

    @Test fun missingTaskIsNotFound() = runTest {
        val s = start(FakeTasksRepository(listOf(task)), "nope").vm.state.value
        assertTrue(s.loaded)
        assertTrue(s.notFound)
    }
}
