package tech.beyondscale.mitra.feature.tasks

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskRowUiTest {
    private val today = "2026-10-13"

    @Test fun mapsLikeWebTaskVm() {
        val parent = Task("p", "Launch v2", due = "2026-10-12", projectId = "pr1", estMin = 65, pri = Priority.High, source = "ai", recurring = "Weekly")
        val kids = listOf(
            Task("c1", "Draft notes", parentId = "p", done = true, status = TaskStatus.Done),
            Task("c2", "Send deck", parentId = "p"),
        )
        val all = listOf(parent) + kids
        val projects = mapOf("pr1" to Project("pr1", "Mitra"))

        val row = taskRowUi(parent, all, projects, today)
        assertEquals(
            TaskRowUi(
                id = "p", title = "Launch v2", done = false, overdue = true, due = "Overdue · Mon 12 Oct", project = "Mitra",
                est = "1h 05m", sub = "1/2", recurring = true, ai = true, parent = null, pri = Priority.High,
            ),
            row,
        )

        val child = taskRowUi(kids[1], all, projects, today)
        assertEquals("Launch v2", child.parent)
        assertEquals(null, child.sub)
        assertEquals(null, child.est)
        assertEquals(false, child.ai)

        assertEquals(null, taskRowUi(parent, all, projects, today, showProject = false).project)
        assertEquals(null, taskRowUi(parent.copy(estMin = 0), all, projects, today).est)
    }
}
