package tech.beyondscale.mitra.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an external entry point (widget, shortcut, notification) asks the app to go. */
sealed interface LaunchTarget {
    data class OpenTask(val id: String) : LaunchTarget
    data object OpenTasks : LaunchTarget
    data object QuickAddTask : LaunchTarget
    data object OpenMoney : LaunchTarget
    data object QuickAddExpense : LaunchTarget
    data object OpenBudgets : LaunchTarget
    data object OpenCheckin : LaunchTarget
    data object OpenAi : LaunchTarget
    /** Shared text / URL from another app (share target): Quick Add as a Note, prefilled. */
    data class QuickAddNote(val text: String) : LaunchTarget
}

private const val TASK_PREFIX = "mitra://task/"

/**
 * `mitra://task/<id>`, `mitra://tasks`, `mitra://quick-add/task`, `mitra://money`, `mitra://quick-add/expense`,
 * `mitra://money/budgets`, `mitra://checkin` or `mitra://ai` (all but the task id matched exactly); anything else is null.
 */
fun parseLaunchUri(uri: String?): LaunchTarget? = when {
    uri == null -> null
    uri == "mitra://tasks" -> LaunchTarget.OpenTasks
    uri == "mitra://quick-add/task" -> LaunchTarget.QuickAddTask
    uri == "mitra://money" -> LaunchTarget.OpenMoney
    uri == "mitra://quick-add/expense" -> LaunchTarget.QuickAddExpense
    uri == "mitra://money/budgets" -> LaunchTarget.OpenBudgets
    uri == "mitra://checkin" -> LaunchTarget.OpenCheckin
    uri == "mitra://ai" -> LaunchTarget.OpenAi
    uri.startsWith(TASK_PREFIX) -> uri.removePrefix(TASK_PREFIX).takeIf { it.isNotBlank() && '/' !in it }?.let(LaunchTarget::OpenTask)
    else -> null
}

/** Shared text (share target): the subject on its own line above the text, unless the text already contains it. */
fun sharedNoteTarget(subject: String?, text: String?): LaunchTarget? {
    val t = text?.trim().orEmpty()
    val s = subject?.trim().orEmpty()
    val joined = if (s.isNotEmpty() && s !in t) listOf(s, t).filter { it.isNotEmpty() }.joinToString("\n") else t
    return joined.takeIf { it.isNotEmpty() }?.let(LaunchTarget::QuickAddNote)
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
