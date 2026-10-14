package tech.beyondscale.mitra.domain.tasks

import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.testing.FakeTasksRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TODAY = "2026-10-14"

class TaskCompletionTest {
    private val open = Task(id = "t1", title = "Call John", due = TODAY, recurring = "Daily")
    private val done = open.copy(done = true, status = TaskStatus.Done, completedAt = TODAY)

    @Test fun toggleCompletesAndReturnsSpawned() = runTest {
        val repo = FakeTasksRepository(listOf(open), today = TODAY).apply { spawnOnComplete = "t2" }
        val c = repo.toggleCompletion("t1")
        assertIs<Completion.Completed>(c)
        assertEquals(open, c.task)
        assertNotNull(c.spawnedId)
        assertEquals("t2", c.spawnedId)
        assertEquals(listOf("done:t1:true"), repo.calls)
    }

    @Test fun toggleReopensDoneTask() = runTest {
        val repo = FakeTasksRepository(listOf(done), today = TODAY)
        val c = repo.toggleCompletion("t1")
        assertIs<Completion.Reopened>(c)
        assertEquals(done, c.task)
        assertEquals(listOf("done:t1:false"), repo.calls)
    }

    @Test fun onCompletingRunsBeforeTheWriteOnlyWhenCompleting() = runTest {
        val repo = FakeTasksRepository(listOf(open), today = TODAY)
        repo.toggleCompletion("t1") { repo.calls += "onCompleting" }
        assertEquals(listOf("onCompleting", "done:t1:true"), repo.calls)
        repo.calls.clear()
        repo.toggleCompletion("t1") { repo.calls += "onCompleting" } // now done → reopen
        assertEquals(listOf("done:t1:false"), repo.calls)
        repo.calls.clear()
        repo.toggleCompletion("nope") { repo.calls += "onCompleting" }
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun unknownIdIsNotFound() = runTest {
        val repo = FakeTasksRepository(listOf(open), today = TODAY)
        assertEquals(Completion.NotFound, repo.toggleCompletion("nope"))
        assertEquals(Completion.NotFound, repo.complete("nope"))
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun undoReopensAndDeletesSpawned() = runTest {
        val repo = FakeTasksRepository(listOf(done, Task(id = "t2", title = "Call John", due = "2026-10-15")), today = TODAY)
        repo.undoCompletion("t1", "t2")
        assertEquals(listOf("done:t1:false", "delete:t2"), repo.calls)
        repo.calls.clear()
        repo.undoCompletion("t1", null)
        assertEquals(listOf("done:t1:false"), repo.calls)
    }

    @Test fun completeNeverReopens() = runTest {
        val repo = FakeTasksRepository(listOf(done), today = TODAY).apply { spawnOnComplete = "t2" }
        assertEquals(Completion.Completed(done, null), repo.complete("t1"))
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun completeCompletesOpenTask() = runTest {
        val repo = FakeTasksRepository(listOf(open), today = TODAY).apply { spawnOnComplete = "t2" }
        assertEquals(Completion.Completed(open, "t2"), repo.complete("t1"))
        assertEquals(listOf("done:t1:true"), repo.calls)
    }
}
