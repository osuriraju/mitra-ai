package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Projects
import tech.beyondscale.mitra.data.db.Tasks
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus

fun Tasks.toDomain(): Task = Task(
    id = id, title = title, projectId = projectId, goalId = goalId, habitId = habitId, due = due, time = time,
    estMin = estMin?.toInt(), pri = Priority.fromWire(pri), status = TaskStatus.fromWire(status), done = done != 0L,
    completedAt = completedAt, parentId = parentId, notes = notes, recurring = recurring, amountPaise = amountPaise,
    someday = someday != 0L, source = source, createdAt = createdAt,
)

fun Task.toRow(): Tasks = Tasks(
    id, title, projectId, goalId, habitId, due, time, estMin?.toLong(), pri?.wire, status.wire, if (done) 1L else 0L,
    completedAt, parentId, notes, recurring, amountPaise, if (someday) 1L else 0L, source, createdAt,
)

fun Projects.toDomain(): Project = Project(id, name, emoji, goalId, createdAt)
fun Project.toRow(): Projects = Projects(id, name, emoji, goalId, createdAt)
