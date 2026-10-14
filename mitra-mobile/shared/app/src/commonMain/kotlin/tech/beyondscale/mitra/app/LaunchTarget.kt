package tech.beyondscale.mitra.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an external entry point (widget, shortcut, notification) asks the app to go. */
sealed interface LaunchTarget {
    data class OpenTask(val id: String) : LaunchTarget
    data object OpenTasks : LaunchTarget
    data object QuickAddTask : LaunchTarget
}

private const val TASK_PREFIX = "mitra://task/"

/** `mitra://task/<id>`, `mitra://tasks` or `mitra://quick-add/task`; anything else is null. */
fun parseLaunchUri(uri: String?): LaunchTarget? = when {
    uri == null -> null
    uri == "mitra://tasks" -> LaunchTarget.OpenTasks
    uri == "mitra://quick-add/task" -> LaunchTarget.QuickAddTask
    uri.startsWith(TASK_PREFIX) -> uri.removePrefix(TASK_PREFIX).takeIf { it.isNotBlank() && '/' !in it }?.let(LaunchTarget::OpenTask)
    else -> null
}

/** Holds the latest launch target until the signed-in shell handles it. */
class LaunchRequests {
    private val target = MutableStateFlow<LaunchTarget?>(null)
    val pending: StateFlow<LaunchTarget?> = target.asStateFlow()

    /** A null (an intent without a recognised URI) leaves any pending target in place. */
    fun submit(t: LaunchTarget?) {
        if (t != null) target.value = t
    }

    fun consume() {
        target.value = null
    }
}
