package tech.beyondscale.mitra.app

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object LoginDest
@Serializable data object SignupDest
@Serializable data object ForgotDest

@Serializable data object TodayDest
@Serializable data object TasksDest
@Serializable data object MoneyDest
@Serializable data object HabitsDest
@Serializable data object GoalsDest
@Serializable data object WellnessDest
@Serializable data object NotesDest
@Serializable data object AiDest
@Serializable data object SettingsDest

@Serializable data class TaskDetailDest(val id: String)
@Serializable data class ProjectDetailDest(val id: String)
/** Focus mode; an empty [taskId] works through today's queue. */
@Serializable data class FocusDest(val taskId: String = "")

/**
 * How much of the shell a route shows: both bars ([Tab]); the bottom bar with the screen drawing its own top bar
 * (a tab, [TabOwnBar], or a back-bar [Detail] screen); or none ([Fullscreen]).
 */
internal enum class Chrome { Tab, TabOwnBar, Detail, Fullscreen }

internal fun chromeFor(route: KClass<*>?): Chrome = when (route) {
    TaskDetailDest::class, ProjectDetailDest::class -> Chrome.Detail
    FocusDest::class -> Chrome.Fullscreen
    TasksDest::class -> Chrome.TabOwnBar
    else -> Chrome.Tab
}

/** Shell-scoped ViewModels are keyed per account, so a signed-in user never inherits the previous account's draft. */
internal fun shellVmKey(name: String, userId: String): String = "$name:$userId"
