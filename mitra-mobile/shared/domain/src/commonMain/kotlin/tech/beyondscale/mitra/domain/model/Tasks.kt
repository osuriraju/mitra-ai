package tech.beyondscale.mitra.domain.model

enum class Priority(val wire: String, val label: String) {
    High("high", "High"), Med("med", "Medium"), Low("low", "Low");

    companion object { fun fromWire(s: String?): Priority? = entries.firstOrNull { it.wire == s } }
}

enum class TaskStatus(val wire: String, val label: String) {
    Todo("todo", "To do"), InProgress("inprogress", "Doing"), Blocked("blocked", "Blocked"), Done("done", "Done");

    companion object { fun fromWire(s: String?): TaskStatus = entries.firstOrNull { it.wire == s } ?: Todo }
}

/** Mirrors `Task` in mitra-frontend/src/store/types.ts; money is kept in paise. Dates are ISO days, `time` is "HH:MM". */
data class Task(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val goalId: String? = null,
    val habitId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val estMin: Int? = null,
    val pri: Priority? = null,
    val status: TaskStatus = TaskStatus.Todo,
    val done: Boolean = false,
    val completedAt: String? = null,
    val parentId: String? = null,
    val notes: String = "",
    val recurring: String? = null,
    val amountPaise: Long? = null,
    val someday: Boolean = false,
    /** user · import · ai · recurring */
    val source: String = "user",
    /** Server timestamp (ISO instant); local creates use the device clock. */
    val createdAt: String = "",
)

data class Project(val id: String, val name: String, val emoji: String = "🚀", val goalId: String? = null, val createdAt: String = "")

val RECURRENCES = listOf("Daily", "Weekly", "Monthly", "Quarterly")

/** What a new task starts with (form, Quick Add, inbox composer, subtask field). */
data class TaskDraft(
    val title: String,
    val projectId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val estMin: Int? = null,
    val pri: Priority? = null,
    val status: TaskStatus = TaskStatus.Todo,
    val notes: String = "",
    val recurring: String? = null,
    val parentId: String? = null,
    val someday: Boolean = false,
    val goalId: String? = null,
)

/** One field edit. A null value clears the field on the server (sent as JSON null). */
sealed interface TaskChange {
    data class Title(val value: String) : TaskChange
    data class ProjectId(val value: String?) : TaskChange
    data class Due(val value: String?) : TaskChange
    data class Time(val value: String?) : TaskChange
    data class EstMin(val value: Int?) : TaskChange
    data class Pri(val value: Priority?) : TaskChange
    /** Also sets `done` / `completedAt` coherently, like the server does. */
    data class Status(val value: TaskStatus) : TaskChange
    data class Notes(val value: String) : TaskChange
    data class Recurring(val value: String?) : TaskChange
    data class Someday(val value: Boolean) : TaskChange
    data class Parent(val value: String?) : TaskChange
    /** Links the task to a goal (evidence only; the goal's progress never derives from it). Null unlinks. */
    data class GoalId(val value: String?) : TaskChange
}
