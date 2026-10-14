package tech.beyondscale.mitra.android.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import tech.beyondscale.mitra.domain.repository.TasksRepository

/**
 * A widget row's checkbox: completes the task locally (DB + outbox, so it reaches the server even if the process
 * dies — the periodic sync job drains it) and redraws this widget at once.
 */
class ToggleTaskAction : ActionCallback, KoinComponent {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskId] ?: return
        get<TasksRepository>().setDone(id, true)
        TasksWidget().update(context, glanceId)
    }

    companion object {
        val TaskId = ActionParameters.Key<String>("taskId")
    }
}
