package tech.beyondscale.mitra.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.SessionState
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
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.feature.common.toggleTask
import tech.beyondscale.mitra.feature.tasks.TaskRowUi
import tech.beyondscale.mitra.feature.tasks.taskRowUi
import kotlin.math.abs

enum class StatTarget { Tasks, Money }

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
)

sealed interface TodayAction {
    data class Toggle(val id: String) : TodayAction
    data class UndoComplete(val id: String, val spawned: String?) : TodayAction
    /** Navigation events for the shell. */
    data object AddExpense : TodayAction
    data object OpenBudgets : TodayAction
}

/** Header, stat strip and the "Now · priorities" section; later modules fill the remaining stats and sections. */
class TodayViewModel(
    auth: AuthRepository,
    private val tasks: TasksRepository,
    money: MoneyRepository,
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

    private class Inputs(val session: SessionState, val tasks: List<Task>, val projects: List<Project>, val money: MoneyData)

    private val moneyData: Flow<MoneyData> =
        combine(money.categories, money.transactions, money.recurring, money.subscriptions, ::MoneyData)

    val state: StateFlow<TodayUiState> =
        combine(auth.session, minuteTicker(), tasks.tasks, tasks.projects, moneyData) { session, _, all, projects, m -> Inputs(session, all, projects, m) }
            .combine(clock.dayFlow()) { i, today -> buildFrom(i.session, Unit, i.tasks, i.projects, i.money, today) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildFrom(auth.session.value, Unit, emptyList(), emptyList(), MoneyData(), clock.today()))

    fun onAction(a: TodayAction) {
        when (a) {
            is TodayAction.Toggle -> viewModelScope.launch {
                queue.toggleTask(tasks, a.id) { TodayAction.UndoComplete(it.id, it.spawnedId) }
            }
            is TodayAction.UndoComplete -> viewModelScope.launch { tasks.undoCompletion(a.id, a.spawned) }
            TodayAction.AddExpense, TodayAction.OpenBudgets -> Unit // handled by the shell (EventEffect → navigation)
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
        val showMoney = (user?.settings?.modules?.money ?: true) && (user?.settings?.todaySections?.contains("money") ?: true)
        return TodayUiState(
            dateLine = "${fmtLong(now.date.toString())} · ${fmtClock(now.hour, now.minute)}",
            greeting = greeting(now.hour) + (user?.firstName?.let { ", $it" } ?: ""),
            stats = listOf(
                StatUi("Tasks", "$done/${open + done}", statSub, StatTarget.Tasks),
                StatUi("Habits", "—", "Due today"),
                StatUi("Spent today", inrPaise(spentToday(m.txs, today)), "$pct% of month budget used", StatTarget.Money),
                StatUi("Sleep", "—", "Last night"),
            ),
            priorities = priorityTasks(all, today).map { taskRowUi(it, all, byId, today) },
            showPriorities = user?.settings?.todaySections?.contains("priorities") ?: true,
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
}
