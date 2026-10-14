package tech.beyondscale.mitra.feature.tasks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
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

    private fun TestScope.start(repo: FakeTasksRepository, goals: FakeGoalsRepository = FakeGoalsRepository(clock = clock)): Harness {
        val vm = TaskEditorViewModel(repo, goals, clock)
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

    @Test fun estimateKeepsAtMostFourDigits() = runTest {
        val h = start(FakeTasksRepository())
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("")))
        h.vm.onAction(TaskEditorAction.Est("12a345"))
        assertEquals("1234", h.vm.state.value.form.est)
    }

    @Test fun parentOptionsExcludeSelfDescendantsAndDoneTasks() = runTest {
        val child = Task("c1", "Outline", parentId = "t1")
        val grandchild = Task("g1", "Intro", parentId = "c1")
        val other = Task("o1", "Plan trip")
        val done = Task("d1", "Old", done = true)
        val repo = FakeTasksRepository(listOf(t1, child, grandchild, other, done))
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("")))
        assertEquals(listOf(ParentOption("t1", "Write report"), ParentOption("o1", "Plan trip")), h.vm.state.value.parentOptions)
        h.vm.onAction(TaskEditorAction.Edit("t1"))
        runCurrent()
        assertEquals(listOf(ParentOption("o1", "Plan trip")), h.vm.state.value.parentOptions)
        // A subtask can keep (and see) its current parent.
        h.vm.onAction(TaskEditorAction.Edit("c1"))
        runCurrent()
        assertEquals(listOf(ParentOption("t1", "Write report"), ParentOption("o1", "Plan trip")), h.vm.state.value.parentOptions)
    }

    @Test fun parentChangeIsSent() = runTest {
        val child = Task("c1", "Outline", parentId = "t1")
        val repo = FakeTasksRepository(listOf(t1, child, Task("o1", "Plan trip")))
        val h = start(repo)
        h.vm.onAction(TaskEditorAction.Edit("c1"))
        runCurrent()
        h.vm.onAction(TaskEditorAction.Parent("o1"))
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        h.vm.onAction(TaskEditorAction.Edit("c1"))
        runCurrent()
        h.vm.onAction(TaskEditorAction.Parent(null))
        assertEquals("Edit task", h.vm.state.value.heading)
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals(listOf("update:c1:Parent(value=o1)", "update:c1:Parent(value=null)"), repo.calls)
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

    @Test fun goalPickerListsActiveGoalsOnly() = runTest {
        val goals = FakeGoalsRepository(
            listOf(
                linkGoal("g1", "Build ₹1,00,000 emergency fund"),
                linkGoal("g2", "Run a half marathon", GoalState.Paused),
                linkGoal("g3", "Read 12 books", GoalState.Completed),
            ),
            clock,
        )
        val repo = FakeTasksRepository(listOf(t1))
        val h = start(repo, goals)
        h.vm.onAction(TaskEditorAction.Edit("t1"))
        runCurrent()
        assertEquals(listOf("g1" to "Build ₹1,00,000 emergency fund"), h.vm.state.value.goalOptions)
        h.vm.onAction(TaskEditorAction.Goal("g1"))
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals(listOf("update:t1:GoalId(value=g1)"), repo.calls)
        assertEquals("g1", repo.taskFlow.value.single().goalId)
    }

    @Test fun clearingTheGoalSendsNull() = runTest {
        val goals = FakeGoalsRepository(listOf(linkGoal("g2", "Run a half marathon", GoalState.Paused)), clock)
        val repo = FakeTasksRepository(listOf(t1.copy(goalId = "g2")))
        val h = start(repo, goals)
        h.vm.onAction(TaskEditorAction.Edit("t1"))
        runCurrent()
        // The paused goal the task already links to stays selectable, so an edit doesn't silently drop it.
        assertEquals(listOf("g2" to "Run a half marathon"), h.vm.state.value.goalOptions)
        assertEquals("g2", h.vm.state.value.form.goalId)
        h.vm.onAction(TaskEditorAction.Goal(null))
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals(listOf("update:t1:GoalId(value=null)"), repo.calls)
    }

    @Test fun createFromGoalKeepsGoalId() = runTest {
        val goals = FakeGoalsRepository(listOf(linkGoal("g1", "Build ₹1,00,000 emergency fund")), clock)
        val repo = FakeTasksRepository()
        val h = start(repo, goals)
        h.vm.onAction(TaskEditorAction.Open(TaskDraft("x", goalId = "g1")))
        assertEquals("g1", h.vm.state.value.form.goalId)
        h.vm.onAction(TaskEditorAction.Save)
        runCurrent()
        assertEquals("g1", repo.taskFlow.value.single().goalId)
    }
}
