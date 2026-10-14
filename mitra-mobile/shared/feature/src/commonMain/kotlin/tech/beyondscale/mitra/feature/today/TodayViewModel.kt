package tech.beyondscale.mitra.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtClock
import tech.beyondscale.mitra.core.fmtLong
import tech.beyondscale.mitra.core.greeting
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.inrShortPaise
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.goals.GoalStatus
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.goals.goalStatus
import tech.beyondscale.mitra.domain.goals.ringTone
import tech.beyondscale.mitra.domain.goals.statusTone
import tech.beyondscale.mitra.domain.habits.habitTap
import tech.beyondscale.mitra.domain.habits.habitsDoneToday
import tech.beyondscale.mitra.domain.habits.habitsDueToday
import tech.beyondscale.mitra.domain.habits.habitsWeekRate
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.money.budgetPct
import tech.beyondscale.mitra.domain.money.daysLeftLabel
import tech.beyondscale.mitra.domain.money.nextLabel
import tech.beyondscale.mitra.domain.money.remainingTitle
import tech.beyondscale.mitra.domain.money.safeLabel
import tech.beyondscale.mitra.domain.money.spent
import tech.beyondscale.mitra.domain.money.spentToday
import tech.beyondscale.mitra.domain.money.thisMonth
import tech.beyondscale.mitra.domain.money.todayMoney
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.doneMin
import tech.beyondscale.mitra.domain.tasks.doneToday
import tech.beyondscale.mitra.domain.tasks.isOverdue
import tech.beyondscale.mitra.domain.tasks.openToday
import tech.beyondscale.mitra.domain.tasks.plannedMin
import tech.beyondscale.mitra.domain.tasks.priorityTasks
import tech.beyondscale.mitra.domain.tasks.undoCompletion
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.feature.common.toggleTask
import tech.beyondscale.mitra.feature.habits.HabitRowUi
import tech.beyondscale.mitra.feature.habits.habitRowUi
import tech.beyondscale.mitra.feature.tasks.TaskRowUi
import tech.beyondscale.mitra.feature.tasks.taskRowUi
import kotlin.math.abs

enum class StatTarget { Tasks, Habits, Money }

data class StatUi(val label: String, val value: String, val sub: String, val target: StatTarget? = null)
data class MoneyCardUi(
    /** "₹1,240" or "—". */
    val safe: String,
    /** Show " / day" after [safe]. */
    val perDay: Boolean,
    val daysLeft: String,
    val remainingTitle: String,
    val remaining: String,
    val remainingDanger: Boolean,
    val budgetSub: String,
    /** "🏠 Next up: Rent · ₹15,000 · in 3 days" */
    val next: String?,
    val alert: String?,
    val alertDanger: Boolean,
)

/** One row of the Today "Goals" card (web `TodayGoals`). */
data class TodayGoalUi(
    val id: String,
    val name: String,
    val pct: Int,
    /** Ring tone ([ringTone]). */
    val tone: StatusTone,
    /** `"Next: Transfer ₹8,000"` or `"Next: —"`. */
    val next: String,
    val status: String,
    val statusTone: StatusTone,
)

/** Section ids the app renders, in `settings.todaySections` order (`activity` / `checkin` arrive later). */
private val BUILT_SECTIONS = setOf("priorities", "habits", "money", "goals")

data class TodayUiState(
    val dateLine: String,
    val greeting: String,
    val stats: List<StatUi>,
    val priorities: List<TaskRowUi> = emptyList(),
    val showPriorities: Boolean = true,
    val prioritiesFooter: String = "All 0 tasks for today",
    val prioritiesMeta: String = "0 done",
    /** Null when the money module or the Today "money" section is off. */
    val money: MoneyCardUi? = null,
    /** Due-today habit rows; null when the "habits" section is off. */
    val habits: List<HabitRowUi>? = null,
    /** Top two active goals, not-on-track first; null when the "goals" section is off. */
    val goals: List<TodayGoalUi>? = null,
    /** Render order of the sections that are on and built. */
    val sections: List<String> = emptyList(),
)

sealed interface TodayAction {
    data class Toggle(val id: String) : TodayAction
    data class UndoComplete(val id: String, val spawned: String?) : TodayAction
    /** Navigation events for the shell. */
    data object AddExpense : TodayAction
    data object OpenBudgets : TodayAction
    data class CheckHabit(val id: String) : TodayAction
    /** Undo of a habit check: put that day's value back. */
    data class SetHabitLog(val id: String, val date: String, val value: Int?) : TodayAction
    data class OpenHabit(val id: String) : TodayAction
    data class OpenGoal(val id: String) : TodayAction
    data object AddHabit : TodayAction
    data object OpenHabits : TodayAction
    data object OpenGoals : TodayAction
}

/** Logs a habit check for [today] with the Habits screen's haptics and snackbar ([undo] builds the Undo action). */
internal suspend fun <A> EventQueue<A>.checkHabit(habits: HabitsRepository, id: String, today: String, undo: (String, String, Int?) -> A) {
    val h = habits.habits.first().firstOrNull { it.id == id } ?: return
    val tap = habitTap(h, today)
    when {
        tap.streakMilestone -> send(UiEvent.Haptic(HapticKind.Milestone))
        tap.reachedTarget -> send(UiEvent.Haptic(HapticKind.Confirm))
    }
    habits.setLog(tap.habitId, tap.date, tap.value)
    val snack = tap.snack ?: return
    send(UiEvent.Snack(snack, if (tap.canUndo) undo(tap.habitId, tap.date, tap.undoValue) else null))
}

/** Header, stat strip and the "Now · priorities" section; later modules fill the remaining stats and sections. */
class TodayViewModel(
    auth: AuthRepository,
    private val tasks: TasksRepository,
    money: MoneyRepository,
    private val habits: HabitsRepository,
    goals: GoalsRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<TodayAction>()
    val events: Flow<UiEvent<TodayAction>> = queue.events

    private data class MoneyData(
        val categories: List<Category> = emptyList(),
        val txs: List<Transaction> = emptyList(),
        val recurring: List<Recurring> = emptyList(),
        val subs: List<Subscription> = emptyList(),
    )

    private class Inputs(val session: SessionState, val tasks: List<Task>, val projects: List<Project>, val money: MoneyData, val hg: HabitsGoals)
    private data class HabitsGoals(val habits: List<Habit> = emptyList(), val goals: List<Goal> = emptyList())

    private val moneyData: Flow<MoneyData> =
        combine(money.categories, money.transactions, money.recurring, money.subscriptions, ::MoneyData)

    val state: StateFlow<TodayUiState> =
        combine(
            combine(auth.session, minuteTicker(), tasks.tasks, tasks.projects, moneyData) { session, _, all, projects, m -> Inputs(session, all, projects, m, HabitsGoals()) },
            combine(habits.habits, goals.goals, ::HabitsGoals),
        ) { i, hg -> Inputs(i.session, i.tasks, i.projects, i.money, hg) }
            .combine(clock.dayFlow()) { i, today -> buildFrom(i.session, Unit, i.tasks, i.projects, i.money, i.hg, today) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildFrom(auth.session.value, Unit, emptyList(), emptyList(), MoneyData(), HabitsGoals(), clock.today()))

    fun onAction(a: TodayAction) {
        when (a) {
            is TodayAction.Toggle -> viewModelScope.launch {
                queue.toggleTask(tasks, a.id) { TodayAction.UndoComplete(it.id, it.spawnedId) }
            }
            is TodayAction.UndoComplete -> viewModelScope.launch { tasks.undoCompletion(a.id, a.spawned) }
            // The date is read at tap time, so a tap after local midnight logs the new day.
            is TodayAction.CheckHabit -> viewModelScope.launch {
                queue.checkHabit(habits, a.id, clock.today()) { id, date, v -> TodayAction.SetHabitLog(id, date, v) }
            }
            is TodayAction.SetHabitLog -> viewModelScope.launch { habits.setLog(a.id, a.date, a.value) }
            TodayAction.AddExpense, TodayAction.OpenBudgets, TodayAction.AddHabit, TodayAction.OpenHabits, TodayAction.OpenGoals,
            is TodayAction.OpenHabit, is TodayAction.OpenGoal,
            -> Unit // handled by the shell (navigation)
        }
    }

    /** Emits now and then at every wall-clock minute boundary so the header never goes stale. */
    private fun minuteTicker() = flow {
        while (true) {
            emit(Unit)
            val intoMinute = clock.now().toEpochMilliseconds().mod(60_000L)
            delay(60_000L - intoMinute)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildFrom(
        session: SessionState,
        tick: Unit,
        all: List<Task>,
        projects: List<Project>,
        m: MoneyData,
        hg: HabitsGoals,
        today: String,
    ): TodayUiState {
        val user = (session as? SessionState.SignedIn)?.user
        val now = clock.localNow()
        val open = openToday(all, today).size
        val done = doneToday(all, today).size
        val overdue = all.count { it.isOverdue(today) }
        val byId = projects.associateBy { it.id }
        val statSub = (if (overdue > 0) "$overdue overdue · " else "") +
            "${minsToHm(plannedMin(all, today))} planned · ${minsToHm(doneMin(all, today))} done"
        val card = todayMoney(m.categories, m.txs, m.recurring, m.subs, today)
        val monthSpent = spent(thisMonth(m.txs, monthKey(today)))
        val pct = budgetPct(monthSpent, card.budgetPaise)
        val moneyOn = user?.settings?.modules?.money ?: true
        val sections = (user?.settings?.todaySections ?: Settings.DEFAULT_TODAY_SECTIONS)
            .filter { it in BUILT_SECTIONS && (it != "money" || moneyOn) }.distinct()
        val showMoney = "money" in sections
        val due = habitsDueToday(hg.habits, today)
        val habitsDone = habitsDoneToday(hg.habits, today).size
        return TodayUiState(
            dateLine = "${fmtLong(now.date.toString())} · ${fmtClock(now.hour, now.minute)}",
            greeting = greeting(now.hour) + (user?.firstName?.let { ", $it" } ?: ""),
            stats = listOf(
                StatUi("Tasks", "$done/${open + done}", statSub, StatTarget.Tasks),
                StatUi("Habits", "$habitsDone/${due.size}", "${habitsWeekRate(hg.habits, today)}% this week", StatTarget.Habits),
                StatUi("Spent today", inrPaise(spentToday(m.txs, today)), "$pct% of month budget used", StatTarget.Money),
                StatUi("Sleep", "—", "Last night"),
            ),
            priorities = priorityTasks(all, today).map { taskRowUi(it, all, byId, today) },
            showPriorities = "priorities" in sections,
            habits = if ("habits" in sections) due.map { habitRowUi(it, today) } else null,
            goals = if ("goals" in sections) todayGoals(hg.goals, hg.habits, today) else null,
            sections = sections,
            prioritiesFooter = "All ${open + overdue + done} tasks for today",
            prioritiesMeta = "$done done" + if (overdue > 0) " · $overdue overdue" else "",
            money = if (!showMoney) null else MoneyCardUi(
                safe = safeLabel(card),
                perDay = card.safePerDayRupees != null,
                daysLeft = daysLeftLabel(card),
                remainingTitle = remainingTitle(card),
                remaining = inrPaise(abs(card.remainingPaise)),
                remainingDanger = card.remainingPaise < 0,
                budgetSub = if (card.budgetPaise != 0L) "of ${inrShortPaise(card.budgetPaise)} budget" else "",
                next = card.next?.let { "${it.emoji} Next up: ${it.name} · ${inrPaise(it.amountPaise)} · ${nextLabel(it)}" },
                alert = card.alert?.text,
                alertDanger = card.alert?.over == true,
            ),
        )
    }

    private fun todayGoals(goals: List<Goal>, habits: List<Habit>, today: String): List<TodayGoalUi> =
        goals.filter { it.state == GoalState.Active }
            .map { g -> Triple(g, goalStatus(g, habits, today), goalProgress(g, habits, today)) }
            .sortedBy { (_, st, _) -> if (st == GoalStatus.OnTrack) 1 else 0 }
            .take(2)
            .map { (g, st, p) ->
                TodayGoalUi(
                    id = g.id,
                    name = g.name,
                    pct = p,
                    tone = ringTone(st),
                    next = "Next: ${g.nextAction.ifBlank { "—" }}",
                    status = st.label,
                    statusTone = statusTone(st),
                )
            }
}
