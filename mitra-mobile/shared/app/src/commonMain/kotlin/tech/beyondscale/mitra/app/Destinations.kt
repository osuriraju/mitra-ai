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

/** Money sub-screens, stacked over the Money tab; an empty [categoryId] lists every transaction. */
@Serializable data class TransactionsDest(val categoryId: String = "")
@Serializable data object BudgetsDest
@Serializable data object CategoriesDest
@Serializable data object RecurringDest
@Serializable data object SubscriptionsDest

/**
 * How much of the shell a route shows: both bars ([Tab]); the bottom bar with the screen drawing its own top bar
 * (a tab, [TabOwnBar], or a back-bar [Detail] screen); or none ([Fullscreen]).
 */
internal enum class Chrome { Tab, TabOwnBar, Detail, Fullscreen }

/** Every route whose chrome isn't [Chrome.Tab]; [chromeForDestination] checks only these. */
private val NON_TAB_ROUTES = listOf(
    TasksDest::class, TaskDetailDest::class, ProjectDetailDest::class, FocusDest::class,
    MoneyDest::class, TransactionsDest::class, BudgetsDest::class, CategoriesDest::class, RecurringDest::class, SubscriptionsDest::class,
)

/** Detail routes that belong to the Money tab (the rest of the detail routes live under Tasks). */
internal val MONEY_DETAIL_ROUTES = listOf(TransactionsDest::class, BudgetsDest::class, CategoriesDest::class, RecurringDest::class, SubscriptionsDest::class)

/** The chrome for the current destination, given its `hasRoute` check (how MainShell resolves it). */
internal fun chromeForDestination(hasRoute: (KClass<*>) -> Boolean): Chrome = chromeFor(NON_TAB_ROUTES.firstOrNull(hasRoute))

internal fun chromeFor(route: KClass<*>?): Chrome = when (route) {
    TaskDetailDest::class, ProjectDetailDest::class -> Chrome.Detail
    in MONEY_DETAIL_ROUTES -> Chrome.Detail
    FocusDest::class -> Chrome.Fullscreen
    TasksDest::class, MoneyDest::class -> Chrome.TabOwnBar
    else -> Chrome.Tab
}

/** Shell-scoped ViewModels are keyed per account, so a signed-in user never inherits the previous account's draft. */
internal fun shellVmKey(name: String, userId: String): String = "$name:$userId"
