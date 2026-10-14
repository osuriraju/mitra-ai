package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.addMonthsJs
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import kotlin.math.roundToInt

/* 1:1 ports of the task selectors in mitra-frontend/src/store/selectors.ts and screens/tasks.tsx.
   The web reads todayISO() implicitly; here every function takes the device's local date. */

/** high 0 · med 1 · low 2 · none 3 (the web's `order` map). */
fun priRank(p: Priority?): Int = when (p) {
    Priority.High -> 0
    Priority.Med -> 1
    Priority.Low -> 2
    null -> 3
}

fun Task.isInbox(): Boolean = projectId == null && due == null && !someday && goalId == null && !done && parentId == null
fun Task.isOverdue(today: String): Boolean = due != null && due < today && !done
fun Task.isToday(today: String): Boolean = due == today
fun Task.isUpcoming(today: String): Boolean = due != null && due > today && !done

fun openToday(tasks: List<Task>, today: String): List<Task> = tasks.filter { it.isToday(today) && !it.done }
fun doneToday(tasks: List<Task>, today: String): List<Task> = tasks.filter { it.done && it.completedAt == today }
fun overdueTasks(tasks: List<Task>, today: String): List<Task> = tasks.filter { it.isOverdue(today) }

/** Up to three open overdue/today tasks: overdue first, then by priority (stable, like the web's sort). */
fun priorityTasks(tasks: List<Task>, today: String): List<Task> =
    tasks.filter { !it.done && (it.isOverdue(today) || it.isToday(today)) }
        .sortedWith(compareBy<Task> { if (it.isOverdue(today)) 0 else 1 }.thenBy { priRank(it.pri) })
        .take(3)

fun plannedMin(tasks: List<Task>, today: String): Int = tasks.filter { it.isToday(today) }.sumOf { it.estMin ?: 0 }
fun doneMin(tasks: List<Task>, today: String): Int = tasks.filter { it.isToday(today) && it.done }.sumOf { it.estMin ?: 0 }

fun children(tasks: List<Task>, id: String): List<Task> = tasks.filter { it.parentId == id }

data class SubProgress(val done: Int, val total: Int)
fun subProgress(tasks: List<Task>, id: String): SubProgress = children(tasks, id).let { c -> SubProgress(c.count { it.done }, c.size) }

/** Buckets: an ISO day (≤ 7 days out), "next-week" (8–14), "later"; ordered days → next week → later. */
fun groupUpcoming(tasks: List<Task>, today: String): List<Pair<String, List<Task>>> {
    val groups = linkedMapOf<String, MutableList<Task>>()
    tasks.filter { it.isUpcoming(today) }.sortedBy { it.due }.forEach { t ->
        val n = diffDays(t.due!!, today)
        val key = when {
            n <= 7 -> t.due
            n <= 14 -> "next-week"
            else -> "later"
        }
        groups.getOrPut(key) { mutableListOf() }.add(t)
    }
    val rank = { k: String -> when (k) { "next-week" -> 1; "later" -> 2; else -> 0 } }
    return groups.entries.sortedWith(compareBy({ rank(it.key) }, { it.key })).map { it.key to it.value.toList() }
}

fun upcomingLabel(key: String, today: String): String = when (key) {
    "next-week" -> "Next week"
    "later" -> "Later"
    else -> fmtDay(key, today) + if (diffDays(key, today) == 1) " · " + fmtDay(key, today, relative = false) else ""
}

/** The Today list: open before done, then by time with untimed last. */
fun todaySorted(tasks: List<Task>, today: String): List<Task> =
    tasks.filter { it.isToday(today) }.sortedWith(compareBy<Task> { it.done }.thenBy { it.time ?: "99" })

data class TaskCounts(val inbox: Int, val today: Int, val upcoming: Int, val projects: Int)

fun taskCounts(tasks: List<Task>, projects: List<Project>, today: String) = TaskCounts(
    inbox = tasks.count { it.isInbox() },
    today = tasks.count { it.isToday(today) || it.isOverdue(today) },
    upcoming = tasks.count { it.isUpcoming(today) },
    projects = projects.size,
)

enum class DueFilter(val label: String) { Overdue("Overdue"), Today("Due today"), Week("This week") }

data class TaskFilters(val projectId: String? = null, val pri: Priority? = null, val due: DueFilter? = null) {
    val active: Boolean get() = projectId != null || pri != null || due != null
}

fun List<Task>.applyFilters(f: TaskFilters, today: String): List<Task> = filter { t ->
    (f.projectId == null || t.projectId == f.projectId) &&
        (f.pri == null || t.pri == f.pri) &&
        when (f.due) {
            null -> true
            DueFilter.Overdue -> t.isOverdue(today)
            DueFilter.Today -> t.isToday(today)
            DueFilter.Week -> t.due != null && t.due <= addDays(today, 7)
        }
}

/** Row due label (web `taskVM`): "Overdue · Mon 12 Oct" · "5:00 PM" (today) · "Tomorrow 9:00 AM" · "Fri 16 Oct". */
fun dueLabel(t: Task, today: String): String? {
    val due = t.due ?: return null
    return when {
        t.isOverdue(today) -> "Overdue · ${fmtDay(due, today, relative = false)}"
        !t.time.isNullOrBlank() -> fmtDay(due, today).let { d -> if (d == "Today") fmtTime(t.time) else "$d ${fmtTime(t.time)}" }
        else -> fmtDay(due, today)
    }
}

/** The Focus card sentence on Tasks · Today. */
fun focusLine(tasks: List<Task>, today: String): String {
    val first = openToday(tasks, today).sortedBy { priRank(it.pri) }.firstOrNull() ?: return "All done for today. Enjoy the evening."
    val the = if (first.title.lowercase().startsWith("the")) "" else "the "
    val time = first.time?.takeIf { it.isNotBlank() }?.let { " — due ${fmtTime(it)}" } ?: ""
    val est = first.estMin?.takeIf { it > 0 }?.let { ", ${minsToHm(it)}" } ?: ""
    return "Start with $the${first.title}$time$est."
}

/** Focus mode queue: the chosen task first (unless it is already done), then today's open tasks by priority. */
fun focusQueue(tasks: List<Task>, today: String, pinnedId: String?): List<Task> {
    val open = openToday(tasks, today).sortedBy { priRank(it.pri) }
    val pinned = pinnedId?.let { id -> tasks.firstOrNull { it.id == id && !it.done } }
    return if (pinned != null) listOf(pinned) + open.filter { it.id != pinned.id } else open
}

data class ProjectStats(val open: Int, val done: Int, val pct: Int)

fun projectStats(tasks: List<Task>, projectId: String): ProjectStats {
    val ts = tasks.filter { it.projectId == projectId }
    val done = ts.count { it.done }
    return ProjectStats(ts.size - done, done, if (ts.isEmpty()) 0 else (done * 100.0 / ts.size).roundToInt())
}

/** Next occurrence of a recurring task — same arithmetic as `nextDue` in mitra-backend/src/tasks/tasks.service.ts. */
fun nextDue(due: String?, recurring: String, today: String): String {
    val d = due ?: today
    return when (recurring) {
        "Daily" -> addDays(d, 1)
        "Weekly" -> addDays(d, 7)
        "Monthly" -> addMonthsJs(d, 1)
        "Quarterly" -> addMonthsJs(d, 3)
        else -> d
    }
}

/** The Monday after [today] (a Monday maps to next week's), like the task detail's "Next Monday" chip. */
fun nextMonday(today: String): String = addDays(today, 7 - dowMon(today))

/* ---------- home-screen widget (spec §10) ---------- */

data class WidgetTaskRow(val id: String, val title: String, val overdue: Boolean, val meta: String?)

data class TasksWidgetModel(val signedIn: Boolean, val done: Int, val total: Int, val rows: List<WidgetTaskRow>) {
    val left: Int get() = rows.size
}

/** Overdue first (oldest first), then due today by priority then time; header numbers match the Today stat. */
fun tasksWidgetModel(tasks: List<Task>, today: String, signedIn: Boolean): TasksWidgetModel {
    val over = overdueTasks(tasks, today).sortedWith(compareBy<Task> { it.due }.thenBy { priRank(it.pri) })
    val open = openToday(tasks, today).sortedWith(compareBy<Task> { priRank(it.pri) }.thenBy { it.time ?: "99" })
    val done = doneToday(tasks, today).size
    val rows = over.map { WidgetTaskRow(it.id, it.title, true, "Overdue · ${fmtDay(it.due!!, today, relative = false)}") } +
        open.map { WidgetTaskRow(it.id, it.title, false, it.time?.takeIf { t -> t.isNotBlank() }?.let(::fmtTime)) }
    return TasksWidgetModel(signedIn, done, open.size + done, rows)
}
