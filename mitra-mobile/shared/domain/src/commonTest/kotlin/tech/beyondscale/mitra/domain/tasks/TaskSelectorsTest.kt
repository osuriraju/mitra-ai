package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TODAY = "2026-10-14" // Wednesday

private fun t(
    id: String, due: String? = null, done: Boolean = false, completedAt: String? = null, pri: Priority? = null,
    estMin: Int? = null, projectId: String? = null, time: String? = null, parentId: String? = null,
    someday: Boolean = false, title: String = id, recurring: String? = null,
) = Task(id = id, title = title, due = due, done = done, completedAt = completedAt, pri = pri, estMin = estMin,
    projectId = projectId, time = time, parentId = parentId, someday = someday, recurring = recurring)

class TaskSelectorsTest {
    @Test fun inboxMeansUnfiledOpenTopLevel() {
        assertTrue(t("a").isInbox())
        assertFalse(t("a", due = TODAY).isInbox())
        assertFalse(t("a", projectId = "p").isInbox())
        assertFalse(t("a", someday = true).isInbox())
        assertFalse(t("a", done = true).isInbox())
        assertFalse(t("a", parentId = "x").isInbox())
        assertFalse(t("a").copy(goalId = "g").isInbox())
    }

    @Test fun overdueTodayUpcoming() {
        assertTrue(t("a", due = "2026-10-13").isOverdue(TODAY))
        assertFalse(t("a", due = "2026-10-13", done = true).isOverdue(TODAY))
        assertTrue(t("a", due = TODAY).isToday(TODAY))
        assertTrue(t("a", due = "2026-10-15").isUpcoming(TODAY))
        assertFalse(t("a", due = "2026-10-15", done = true).isUpcoming(TODAY))
    }

    @Test fun usesTheDateItIsGiven() {
        // 00:30 IST on the 14th is still the 13th in UTC: the caller's local date decides.
        val task = t("a", due = "2026-10-13")
        assertTrue(task.isToday("2026-10-13"))
        assertTrue(task.isOverdue("2026-10-14"))
        assertEquals(listOf(task), openToday(listOf(task), "2026-10-13"))
        assertEquals(emptyList(), openToday(listOf(task), "2026-10-14"))
    }

    @Test fun doneTodayCountsByCompletionDate() {
        val tasks = listOf(
            t("a", due = "2026-10-01", done = true, completedAt = TODAY),
            t("b", due = TODAY, done = true, completedAt = "2026-10-13"),
            t("c", due = TODAY),
        )
        assertEquals(listOf("a"), doneToday(tasks, TODAY).map { it.id })
        assertEquals(listOf("c"), openToday(tasks, TODAY).map { it.id })
    }

    @Test fun priorityTasksPutOverdueFirstThenPriorityAndKeepThree() {
        val tasks = listOf(
            t("a", due = TODAY, pri = Priority.Low),
            t("b", due = "2026-10-10"),
            t("c", due = TODAY, pri = Priority.High),
            t("d", due = TODAY, pri = Priority.Med),
            t("e", due = TODAY, pri = Priority.High, done = true),
        )
        assertEquals(listOf("b", "c", "d"), priorityTasks(tasks, TODAY).map { it.id })
    }

    @Test fun plannedAndDoneMinutesCountOnlyToday() {
        val tasks = listOf(
            t("a", due = TODAY, estMin = 30, done = true, completedAt = TODAY),
            t("b", due = TODAY, estMin = 45),
            t("c", due = TODAY),
            t("d", due = "2026-10-13", estMin = 60),
        )
        assertEquals(75, plannedMin(tasks, TODAY))
        assertEquals(30, doneMin(tasks, TODAY))
    }

    @Test fun groupUpcomingMatchesWebBuckets() {
        val tasks = listOf(
            t("x", due = "2026-10-22"), t("a", due = "2026-10-15"), t("b", due = "2026-10-15"),
            t("c", due = "2026-10-21"), t("y", due = "2026-10-28"), t("z", due = "2026-10-29"), t("old", due = "2026-10-01"),
        )
        val groups = groupUpcoming(tasks, TODAY)
        assertEquals(listOf("2026-10-15", "2026-10-21", "next-week", "later"), groups.map { it.first })
        assertEquals(listOf("a", "b"), groups[0].second.map { it.id })
        assertEquals(listOf("x", "y"), groups[2].second.map { it.id })
        assertEquals("Tomorrow · Thu 15 Oct", upcomingLabel("2026-10-15", TODAY))
        assertEquals("Wed 21 Oct", upcomingLabel("2026-10-21", TODAY))
        assertEquals("Next week", upcomingLabel("next-week", TODAY))
        assertEquals("Later", upcomingLabel("later", TODAY))
    }

    @Test fun todaySortedPutsOpenFirstThenByTime() {
        val tasks = listOf(
            t("late", due = TODAY, time = "18:00"), t("untimed", due = TODAY), t("done", due = TODAY, time = "07:00", done = true),
            t("early", due = TODAY, time = "09:30"),
        )
        assertEquals(listOf("early", "late", "untimed", "done"), todaySorted(tasks, TODAY).map { it.id })
    }

    @Test fun countsAndFilters() {
        val tasks = listOf(
            t("inbox"), t("over", due = "2026-10-13", projectId = "p1", pri = Priority.High),
            t("today", due = TODAY), t("soon", due = "2026-10-20", pri = Priority.High), t("far", due = "2026-11-30"),
        )
        assertEquals(TaskCounts(inbox = 1, today = 2, upcoming = 2, projects = 1), taskCounts(tasks, listOf(Project("p1", "Work")), TODAY))
        assertEquals(listOf("over"), tasks.applyFilters(TaskFilters(projectId = "p1"), TODAY).map { it.id })
        assertEquals(listOf("over", "soon"), tasks.applyFilters(TaskFilters(pri = Priority.High), TODAY).map { it.id })
        assertEquals(listOf("over", "today", "soon"), tasks.applyFilters(TaskFilters(due = DueFilter.Week), TODAY).map { it.id })
        assertEquals(listOf("over"), tasks.applyFilters(TaskFilters(due = DueFilter.Overdue), TODAY).map { it.id })
    }

    @Test fun dueLabelsMatchTaskVm() {
        assertEquals("Overdue · Mon 12 Oct", dueLabel(t("a", due = "2026-10-12"), TODAY))
        assertEquals("5:00 PM", dueLabel(t("a", due = TODAY, time = "17:00"), TODAY))
        assertEquals("Tomorrow 9:00 AM", dueLabel(t("a", due = "2026-10-15", time = "09:00"), TODAY))
        assertEquals("Tomorrow", dueLabel(t("a", due = "2026-10-15"), TODAY))
        assertEquals(null, dueLabel(t("a"), TODAY))
    }

    @Test fun focusLineMatchesWeb() {
        val tasks = listOf(
            t("a", due = TODAY, title = "Email Ravi"),
            t("b", due = TODAY, title = "Write report", pri = Priority.High, time = "17:00", estMin = 45),
        )
        assertEquals("Start with the Write report — due 5:00 PM, 45m.", focusLine(tasks, TODAY))
        assertEquals("Start with The deck.", focusLine(listOf(t("c", due = TODAY, title = "The deck")), TODAY))
        assertEquals("All done for today. Enjoy the evening.", focusLine(emptyList(), TODAY))
    }

    @Test fun focusQueuePinsTheChosenTaskUnlessDone() {
        val tasks = listOf(t("low", due = TODAY, pri = Priority.Low), t("high", due = TODAY, pri = Priority.High), t("pinned", due = "2026-10-20"))
        assertEquals(listOf("pinned", "high", "low"), focusQueue(tasks, TODAY, "pinned").map { it.id })
        assertEquals(listOf("high", "low"), focusQueue(tasks.map { if (it.id == "pinned") it.copy(done = true) else it }, TODAY, "pinned").map { it.id })
        assertEquals(listOf("high", "low"), focusQueue(tasks, TODAY, null).map { it.id })
    }

    @Test fun projectStatsAndSubProgress() {
        val tasks = listOf(t("a", projectId = "p", done = true), t("b", projectId = "p"), t("c", projectId = "p"), t("s1", parentId = "b", done = true), t("s2", parentId = "b"))
        assertEquals(ProjectStats(open = 2, done = 1, pct = 33), projectStats(tasks, "p"))
        assertEquals(ProjectStats(open = 0, done = 0, pct = 0), projectStats(tasks, "none"))
        assertEquals(SubProgress(done = 1, total = 2), subProgress(tasks, "b"))
    }

    @Test fun nextDueUsesServerArithmetic() {
        assertEquals("2026-10-15", nextDue(TODAY, "Daily", TODAY))
        assertEquals("2026-10-21", nextDue(TODAY, "Weekly", TODAY))
        assertEquals("2026-03-03", nextDue("2026-01-31", "Monthly", TODAY))
        assertEquals("2027-01-14", nextDue(TODAY, "Quarterly", TODAY))
        assertEquals("2026-10-15", nextDue(null, "Daily", TODAY))
        assertEquals("2026-10-19", nextMonday(TODAY))
        assertEquals("2026-10-26", nextMonday("2026-10-19"))
    }

    @Test fun widgetModelListsOverdueThenTodayByPriorityThenTime() {
        val tasks = listOf(
            t("today-low", due = TODAY, pri = Priority.Low),
            t("today-high-late", due = TODAY, pri = Priority.High, time = "18:00"),
            t("today-high-early", due = TODAY, pri = Priority.High, time = "08:00"),
            t("over-new", due = "2026-10-13"), t("over-old", due = "2026-10-10", title = "Old one"),
            t("done", due = TODAY, done = true, completedAt = TODAY),
        )
        val m = tasksWidgetModel(tasks, TODAY, signedIn = true)
        assertEquals(listOf("over-old", "over-new", "today-high-early", "today-high-late", "today-low"), m.rows.map { it.id })
        assertEquals(WidgetTaskRow("over-old", "Old one", overdue = true, meta = "Overdue · Sat 10 Oct"), m.rows[0])
        assertEquals("8:00 AM", m.rows[2].meta)
        assertEquals(1, m.done)
        assertEquals(4, m.total) // open today (3) + done today (1), like the Today stat
        assertEquals(5, m.left)
        assertFalse(tasksWidgetModel(tasks, TODAY, signedIn = false).signedIn)
    }
}
