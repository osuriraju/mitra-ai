package tech.beyondscale.mitra.feature.tasks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Tue 13 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val today = "2026-10-13"
    private val t1 = Task("t1", "Write report", due = today, pri = Priority.Med, projectId = "p1")

    private class Harness(val vm: TaskEditorViewModel, val events: List<UiEvent<TaskEditorAction>>)

    private fun TestScope.start(repo: FakeTasksRepository): Harness {
        val vm = TaskEditorViewModel(repo, clock)
        val events = mutableListOf<UiEvent<TaskEditorAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun blankTitleShowsErrorAndKeepsSheetOpen() = runTest {
        val repo = FakeTasksRepository()
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("")))
        h.vm.onAction(TaskEditorAction.Title("   "))
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        val form = h.vm.state.value.form
        assertTrue(form.open)
        assertEquals("Give the task a title", form.error)
        assertEquals(emptyList(), repo.calls)
    }

    @Test fun createsWithAllFields() = runTest {
        val repo = FakeTasksRepository(projects = listOf(Project("p1", "Mitra")))
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("", due = today)))
        h.vm.onAction(TaskEditorAction.Title("Write"))
        h.vm.onAction(TaskEditorAction.Pri(Priority.High))
        h.vm.onAction(TaskEditorAction.Est("4x5"))
        assertEquals("45", h.vm.state.value.form.est)
        assertEquals(today, h.vm.state.value.form.due)
        assertEquals(listOf(Project("p1", "Mitra")), h.vm.state.value.projects)
        assertEquals(today, h.vm.state.value.today)
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals(listOf("create:Write"), repo.calls)
        val created = repo.taskFlow.value.single()
        assertEquals(Priority.High, created.pri)
        assertEquals(45, created.estMin)
        assertEquals(today, created.due)
        assertEquals(listOf(UiEvent.Snack("Task added · Write", TaskEditorAction.DeleteCreated("new1"))), h.events)
        assertFalse(h.vm.state.value.form.open)

        h.vm.onAction(TaskEditorAction.DeleteCreated("new1"))
        runCurrent()
        assertEquals(listOf("create:Write", "delete:new1"), repo.calls)
    }

    @Test fun editSendsOnlyChangedFields() = runTest {
        val repo = FakeTasksRepository(listOf(t1))
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Edit("t1"))
        runCurrent()
        val form = h.vm.state.value.form
        assertEquals("Write report", form.title)
        assertEquals(Priority.Med, form.pri)
        assertEquals("p1", form.projectId)
        h.vm.onAction(TaskEditorAction.Title("New"))
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals(listOf("update:t1:Title(value=New)"), repo.calls)
        assertEquals(listOf(UiEvent.Snack<TaskEditorAction>("Task updated")), h.events)
        assertFalse(h.vm.state.value.form.open)
    }

    @Test fun editWithoutChangesJustCloses() = runTest {
        val repo = FakeTasksRepository(listOf(t1))
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Edit("t1"))
        runCurrent()
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals(emptyList(), repo.calls)
        assertEquals(emptyList(), h.events)
        assertFalse(h.vm.state.value.form.open)
    }

    @Test fun deletedOffersRestore() = runTest {
        val repo = FakeTasksRepository()
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Deleted(listOf(t1)))
        runCurrent()
        assertEquals(listOf(UiEvent.Snack("Task deleted", TaskEditorAction.Restore(listOf(t1)))), h.events)
        h.vm.onAction(TaskEditorAction.Restore(listOf(t1)))
        runCurrent()
        assertEquals(listOf("restore:t1"), repo.calls)
    }

    @Test fun headingFollowsMode() = runTest {
        val repo = FakeTasksRepository(listOf(t1))
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("")))
        assertEquals("New task", h.vm.state.value.heading)
        h.vm.onAction(TaskEditorAction.Dismiss)
        assertFalse(h.vm.state.value.form.open)
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("", parentId = "t1")))
        assertEquals("New subtask", h.vm.state.value.heading)
        h.vm.onAction(TaskEditorAction.Edit("t1"))
        runCurrent()
        assertEquals("Edit task", h.vm.state.value.heading)
    }
}
