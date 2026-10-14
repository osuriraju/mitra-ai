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

/** Habits and Goals sub-screens, stacked over their list. */
@Serializable data class HabitDetailDest(val id: String)
@Serializable data class HabitHistoryDest(val id: String)
@Serializable data class GoalDetailDest(val id: String)
@Serializable data object GoalReviewDest

/** Wellness, Notes and AI sub-screens. WellnessDest is the check-in; NotesDest the list; AiDest the chat. */
@Serializable data object WellnessTrendsDest
@Serializable data object SleepDetailDest
@Serializable data object WellnessSettingsDest
@Serializable data object NotesSearchDest
@Serializable data object JournalDest
/** [startWriting] opens a note just created straight into the editor's writing mode. */
@Serializable data class NoteEditorDest(val id: String, val startWriting: Boolean = false)
@Serializable data object AiSuggestionsDest
@Serializable data object AiLogDest

/** Global search (web ⌘K), Profile and notification settings — stacked over the current tab / Settings. */
@Serializable data object SearchDest
@Serializable data object ProfileDest
@Serializable data object NotificationSettingsDest

/**
 * How much of the shell a route shows: both bars ([Tab]); the bottom bar with the screen drawing its own top bar
 * (a tab, [TabOwnBar], or a back-bar [Detail] screen); or none ([Fullscreen]).
 */
internal enum class Chrome { Tab, TabOwnBar, Detail, Fullscreen }

/** Every route whose chrome isn't [Chrome.Tab]; [chromeForDestination] checks only these. */
private val NON_TAB_ROUTES = listOf(
    TasksDest::class, TaskDetailDest::class, ProjectDetailDest::class, FocusDest::class,
    MoneyDest::class, TransactionsDest::class, BudgetsDest::class, CategoriesDest::class, RecurringDest::class, SubscriptionsDest::class,
    HabitsDest::class, HabitDetailDest::class, HabitHistoryDest::class, GoalsDest::class, GoalDetailDest::class, GoalReviewDest::class,
    WellnessDest::class, WellnessTrendsDest::class, SleepDetailDest::class, WellnessSettingsDest::class,
    NotesDest::class, NotesSearchDest::class, JournalDest::class, NoteEditorDest::class,
    AiDest::class, AiSuggestionsDest::class, AiLogDest::class,
    SearchDest::class, ProfileDest::class, NotificationSettingsDest::class,
)

/** Detail routes that belong to the Money tab (the rest of the detail routes live under Tasks). */
internal val MONEY_DETAIL_ROUTES = listOf(TransactionsDest::class, BudgetsDest::class, CategoriesDest::class, RecurringDest::class, SubscriptionsDest::class)

/** Detail routes that belong to Habits / Goals (highlighting that tab or More entry). */
internal val HABIT_DETAIL_ROUTES = listOf(HabitDetailDest::class, HabitHistoryDest::class)
internal val GOAL_DETAIL_ROUTES = listOf(GoalDetailDest::class, GoalReviewDest::class)
internal val WELLNESS_DETAIL_ROUTES = listOf(WellnessTrendsDest::class, SleepDetailDest::class, WellnessSettingsDest::class)
internal val NOTES_DETAIL_ROUTES = listOf(NotesSearchDest::class, JournalDest::class, NoteEditorDest::class)
internal val AI_DETAIL_ROUTES = listOf(AiSuggestionsDest::class, AiLogDest::class)
internal val SETTINGS_DETAIL_ROUTES = listOf(ProfileDest::class, NotificationSettingsDest::class)

/** The chrome for the current destination, given its `hasRoute` check (how MainShell resolves it). */
internal fun chromeForDestination(hasRoute: (KClass<*>) -> Boolean): Chrome = chromeFor(NON_TAB_ROUTES.firstOrNull(hasRoute))

internal fun chromeFor(route: KClass<*>?): Chrome = when (route) {
    TaskDetailDest::class, ProjectDetailDest::class -> Chrome.Detail
    in MONEY_DETAIL_ROUTES, in HABIT_DETAIL_ROUTES, in GOAL_DETAIL_ROUTES -> Chrome.Detail
    in WELLNESS_DETAIL_ROUTES, in NOTES_DETAIL_ROUTES, in AI_DETAIL_ROUTES -> Chrome.Detail
    in SETTINGS_DETAIL_ROUTES, SearchDest::class -> Chrome.Detail
    FocusDest::class -> Chrome.Fullscreen
    TasksDest::class, MoneyDest::class, HabitsDest::class, GoalsDest::class -> Chrome.TabOwnBar
    WellnessDest::class, NotesDest::class, AiDest::class -> Chrome.TabOwnBar
    else -> Chrome.Tab
}

/** How "Manage categories" in the transaction sheet reaches [CategoriesDest]. */
internal enum class CategoriesNav { InMoneyStack, ViaMoneyRoot, Unavailable }

/**
 * Inside the Money stack Categories is pushed (or popped back to if already there); from elsewhere (Today, Tasks) it
 * goes through Money's root, so it sits in the stack the highlighted Money tab implies. Nothing with Money off.
 */
internal fun categoriesNav(moneyOn: Boolean, inMoneyStack: Boolean): CategoriesNav = when {
    !moneyOn -> CategoriesNav.Unavailable
    inMoneyStack -> CategoriesNav.InMoneyStack
    else -> CategoriesNav.ViaMoneyRoot
}

/** Shell-scoped ViewModels are keyed per account, so a signed-in user never inherits the previous account's draft. */
internal fun shellVmKey(name: String, userId: String): String = "$name:$userId"
