package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskPatchesTest {
    private val base = Task("t1", "Write", due = "2026-10-13", pri = Priority.Low)

    @Test fun changesBecomeAPatchWithExplicitNulls() {
        val (t, patch) = applyChanges(base, listOf(TaskChange.Title("  Write v2 "), TaskChange.Due(null), TaskChange.Pri(Priority.High), TaskChange.EstMin(45)), "2026-10-14")
        assertEquals(base.copy(title = "Write v2", due = null, pri = Priority.High, estMin = 45), t)
        assertEquals("""{"title":"Write v2","due":null,"pri":"high","estMin":45}""", patch.toString())
    }

    @Test fun statusKeepsDoneAndCompletedAtCoherent() {
        val (done, p1) = applyChanges(base, listOf(TaskChange.Status(TaskStatus.Done)), "2026-10-14")
        assertEquals(true, done.done); assertEquals("2026-10-14", done.completedAt)
        assertEquals("""{"status":"done","done":true,"completedAt":"2026-10-14"}""", p1.toString())
        val (blocked, p2) = applyChanges(done, listOf(TaskChange.Status(TaskStatus.Blocked)), "2026-10-15")
        assertEquals(false, blocked.done); assertEquals(null, blocked.completedAt)
        assertEquals("""{"status":"blocked","done":false,"completedAt":null}""", p2.toString())
    }

    @Test fun completionReopensToTodoOnlyFromDone() {
        val doing = base.copy(status = TaskStatus.InProgress)
        val (closed, patch) = completion(doing, true, "2026-10-14")
        assertEquals(TaskStatus.Done, closed.status)
        assertEquals("""{"status":"done","done":true,"completedAt":"2026-10-14"}""", patch.toString())
        assertEquals(TaskStatus.Todo, completion(closed, false, "2026-10-14").first.status)
        assertEquals(TaskStatus.InProgress, completion(doing.copy(done = true), false, "2026-10-14").first.status)
    }

    @Test fun goalIdNullClears() {
        val linked = base.copy(goalId = "g1")
        val (cleared, p1) = applyChanges(linked, listOf(TaskChange.GoalId(null)), "2026-10-14")
        assertEquals(null, cleared.goalId)
        assertEquals("""{"goalId":null}""", p1.toString())
        val (set, p2) = applyChanges(base, listOf(TaskChange.GoalId("g1")), "2026-10-14")
        assertEquals("g1", set.goalId)
        assertEquals("""{"goalId":"g1"}""", p2.toString())
    }
}
