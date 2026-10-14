package tech.beyondscale.mitra.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addMonthsKey
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.daysInMonth
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.core.monthTitle
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.AccountKind
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.money.BudgetTone
import tech.beyondscale.mitra.domain.money.accountUtilisation
import tech.beyondscale.mitra.domain.money.budgetPct
import tech.beyondscale.mitra.domain.money.budgetTone
import tech.beyondscale.mitra.domain.money.categoryBreakdown
import tech.beyondscale.mitra.domain.money.dailySpend
import tech.beyondscale.mitra.domain.money.income
import tech.beyondscale.mitra.domain.money.monthsWithData
import tech.beyondscale.mitra.domain.money.projectedMonthEnd
import tech.beyondscale.mitra.domain.money.recentTransactions
import tech.beyondscale.mitra.domain.money.savingsRatePct
import tech.beyondscale.mitra.domain.money.spent
import tech.beyondscale.mitra.domain.money.spentByCategory
import tech.beyondscale.mitra.domain.money.thisMonth
import tech.beyondscale.mitra.domain.money.topOutflows
import tech.beyondscale.mitra.domain.money.totalBudget
import tech.beyondscale.mitra.domain.money.transferred
import tech.beyondscale.mitra.domain.money.upcoming
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.math.abs

/** Where the dashboard's links and the top-bar ＋ lead. */
enum class MoneyLink { AddExpense, Transactions, Budgets, Categories, Recurring, Subscriptions }

/** One chip of the account filter; `id == null` is "All accounts". */
data class DashAccountFilter(val id: String?, val label: String)

data class DashBudgetUi(val id: String, val emoji: String, val name: String, val spent: String, val budget: String, val fraction: Float, val tone: BudgetTone)

data class DashAccountUi(val id: String, val name: String, val mask: String, val balance: String, val negative: Boolean, val credit: Boolean)

data class DashUpcomingUi(val id: String, val emoji: String, val name: String, val whenLabel: String, val amount: String, val isSubscription: Boolean)

data class MoneySummaryUi(
    val spent: String = "₹0",
    val spentSub: String = "",
    val income: String = "₹0",
    val incomeSub: String = "none yet",
    val saved: String = "₹0",
    val savedSub: String = "income − expenses",
    /** 1 when income beats expenses, −1 when it falls short, 0 when even. */
    val savedDirection: Int = 0,
    val budgetFraction: Float = 0f,
    val budgetTone: BudgetTone = BudgetTone.Normal,
    val budgetFooter: String = "",
    /** "Projected month end: ₹…" — current month only. */
    val projected: String? = null,
    val daily: List<Long> = emptyList(),
    val dailyLabels: List<String> = emptyList(),
    val dailyHighlight: Int = -1,
    /** Non-null when nothing was spent in the month: replaces the chart. */
    val cashFlowEmpty: String? = null,
    val cashFlowCaption: String = "",
    val recent: List<TxRowUi> = emptyList(),
    val recentEmptyTitle: String = "",
    val recentEmptyBody: String = "",
    val budgets: List<DashBudgetUi> = emptyList(),
    val accounts: List<DashAccountUi> = emptyList(),
    val upcoming: List<DashUpcomingUi> = emptyList(),
)

/** A donut slice; [colorIndex] picks a series colour, null for the folded "Other" slice. */
data class DashSliceUi(val label: String, val paise: Long, val share: String, val colorIndex: Int?)

data class DashRankUi(val id: String, val emoji: String, val name: String, val amount: String, val share: String, val count: Int, val fraction: Float, val colorIndex: Int?)

data class DashAccountUseUi(val id: String, val name: String, val mask: String, val amount: String, val share: String, val count: Int, val fraction: Float, val credit: Boolean)

data class DashOutflowUi(val id: String, val rank: Int, val title: String, val date: String, val category: String, val account: String, val amount: String)

data class MoneyAnalyticsUi(
    val empty: Boolean = true,
    val emptyTitle: String = "",
    val totalIncome: String = "₹0",
    val totalExpenses: String = "₹0",
    val expensesSub: String = "0 debits & charges",
    val net: String = "₹0",
    val netSub: String = "Income − expenses",
    val savingsRate: String = "—",
    val spentPaise: Long = 0,
    val donut: List<DashSliceUi> = emptyList(),
    val ranking: List<DashRankUi> = emptyList(),
    val rankingCaption: String = "",
    val accountUse: List<DashAccountUseUi> = emptyList(),
    val topOutflows: List<DashOutflowUi> = emptyList(),
)

data class MoneyUiState(
    val today: String = "",
    val month: String = "",
    val monthTitle: String = "",
    val isCurrentMonth: Boolean = true,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val accountFilters: List<DashAccountFilter> = listOf(DashAccountFilter(null, "All accounts")),
    val selectedAccount: String? = null,
    /** 0 = Summary, 1 = Analytics. */
    val tab: Int = 0,
    val refreshing: Boolean = false,
    val summary: MoneySummaryUi = MoneySummaryUi(),
    val analytics: MoneyAnalyticsUi = MoneyAnalyticsUi(),
)

sealed interface MoneyAction {
    data object PrevMonth : MoneyAction
    data object NextMonth : MoneyAction
    data class SelectAccount(val id: String?) : MoneyAction
    data class SelectTab(val index: Int) : MoneyAction
    data object Refresh : MoneyAction
}

/** Port of `MoneyScreen` / `MoneySummary` / `MoneyAnalytics` (mitra-frontend/src/screens/money.tsx). */
class MoneyViewModel(private val money: MoneyRepository, clock: AppClock) : ViewModel() {
    private data class Local(
        /** Null follows the current month, so it rolls over at local midnight. */
        val month: String? = null,
        val account: String? = null,
        val tab: Int = 0,
        val refreshing: Boolean = false,
    )

    private data class Data(
        val accounts: List<Account>,
        val categories: List<Category>,
        val transactions: List<Transaction>,
        val recurring: List<Recurring>,
        val subscriptions: List<Subscription>,
    )

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<MoneyAction>()
    val events: Flow<UiEvent<MoneyAction>> = queue.events

    private val data = combine(money.accounts, money.categories, money.transactions, money.recurring, money.subscriptions, ::Data)

    val state: StateFlow<MoneyUiState> = combine(data, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneyUiState())

    fun onAction(a: MoneyAction) {
        when (a) {
            MoneyAction.PrevMonth -> {
                val s = state.value
                if (s.canPrev) setMonth(addMonthsKey(s.month, -1), s.today)
            }
            MoneyAction.NextMonth -> {
                val s = state.value
                if (s.canNext) setMonth(addMonthsKey(s.month, 1), s.today)
            }
            is MoneyAction.SelectAccount -> local.update { it.copy(account = a.id) }
            is MoneyAction.SelectTab -> local.update { it.copy(tab = a.index.coerceIn(0, 1)) }
            MoneyAction.Refresh -> refresh()
        }
    }

    private fun setMonth(month: String, today: String) {
        local.update { it.copy(month = if (month == monthKey(today)) null else month) }
    }

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val result = money.refresh()
                // A superseded refresh is reconciled by the module refresher once the queue drains — not an error.
                val superseded = result is Outcome.Err && (result.error as? AppError.Server)?.code == MoneyRepository.REFRESH_SUPERSEDED
                if (result is Outcome.Err && !superseded) {
                    val text = if (result.error is AppError.Offline) "You're offline — showing money saved on this phone" else result.error.message
                    queue.send(UiEvent.Snack(text))
                }
            } finally {
                local.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun build(d: Data, today: String, l: Local): MoneyUiState {
        val current = monthKey(today)
        val months = monthsWithData(d.transactions, today)
        // A month picked before midnight may now be "next month" relative to a clock that went back; clamp.
        val month = l.month?.takeIf { it < current } ?: current
        val account = l.account?.takeIf { id -> d.accounts.any { it.id == id } }
        val txAll = if (account == null) d.transactions else d.transactions.filter { it.accountId == account || it.toAccountId == account }
        val tx = thisMonth(txAll, month)
        return MoneyUiState(
            today = today,
            month = month,
            monthTitle = monthTitle(month),
            isCurrentMonth = month == current,
            canPrev = month > months.last(),
            canNext = month < current,
            accountFilters = listOf(DashAccountFilter(null, "All accounts")) + d.accounts.map { DashAccountFilter(it.id, "${it.name} ${it.mask}".trim()) },
            selectedAccount = account,
            tab = l.tab,
            refreshing = l.refreshing,
            summary = summary(d, tx, month, today),
            analytics = analytics(d, tx, month, today),
        )
    }

    private fun summary(d: Data, tx: List<Transaction>, month: String, today: String): MoneySummaryUi {
        val cur = month == monthKey(today)
        val dim = daysInMonth("$month-01")
        val day = if (cur) dayOfMonth(today) else dim
        val sp = spent(tx)
        val inc = income(tx)
        val tr = transferred(tx)
        val budget = totalBudget(d.categories)
        val incomeCount = tx.count { it.kind == TxKind.Income }
        val net = inc - sp
        val daily = dailySpend(tx, month, today)
        val byCat = spentByCategory(tx)
        val title = monthTitle(month)
        return MoneySummaryUi(
            spent = inrPaise(sp),
            spentSub = if (budget > 0) "of ${inrPaise(budget)} budget" else "",
            income = inrPaise(inc),
            incomeSub = if (inc > 0) "$incomeCount credit${if (incomeCount > 1) "s" else ""}" else "none yet",
            saved = (if (net < 0) "−" else "") + inrPaise(net),
            savedSub = if (inc > 0) {
                "${jsRound(net.toDouble() / inc * 100)}% of income" + if (tr > 0) " · ${inrPaise(tr)} moved between accounts" else ""
            } else "income − expenses",
            savedDirection = net.compareTo(0L).coerceIn(-1, 1),
            budgetFraction = if (budget > 0) (sp.toDouble() / budget).toFloat() else 0f,
            budgetTone = if (budget > 0) budgetTone(sp, budget) else BudgetTone.Normal,
            budgetFooter = "${budgetPct(sp, budget)}% of budget" + if (cur) " · ${dim - day} days left" else " · $title",
            projected = if (cur) "Projected month end: ${inrPaise(projectedMonthEnd(sp, month, today))}" else null,
            daily = daily.map { it.paise },
            dailyLabels = daily.map { it.label },
            dailyHighlight = if (cur) daily.size - 1 else -1,
            cashFlowEmpty = if (sp == 0L) "Nothing spent in $title yet." else null,
            cashFlowCaption = "Spent per day · " + (if (cur) "last 16 days · today highlighted" else title) + " · press and hold a bar for the amount",
            recent = recentTransactions(tx, 5).map { txRowUi(it, d.categories, d.accounts, today) },
            recentEmptyTitle = if (cur) "No expenses yet this month" else "Nothing recorded in $title",
            recentEmptyBody = if (cur) "Try typing “Spent ₹450 on dinner” in Quick Add — it takes two seconds." else "",
            budgets = d.categories
                .filter { !it.archived && (it.budgetPaise ?: 0L) > 0L }
                .sortedByDescending { (byCat[it.id] ?: 0L).toDouble() / it.budgetPaise!! }
                .take(4)
                .map { c ->
                    val v = byCat[c.id] ?: 0L
                    val b = c.budgetPaise!!
                    DashBudgetUi(c.id, c.emoji, c.name, inrPaise(v), "/ ${inrPaise(b)}", (v.toDouble() / b).toFloat(), budgetTone(v, b))
                },
            accounts = d.accounts.map { a ->
                DashAccountUi(a.id, a.name, a.mask, (if (a.balancePaise < 0) "−" else "") + inrPaise(a.balancePaise), a.balancePaise < 0, a.kind == AccountKind.Credit)
            },
            upcoming = upcoming(d.recurring, d.subscriptions, today, 3).map { u ->
                val whenLabel = when (u.days) {
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> "in ${u.days} days"
                }
                DashUpcomingUi(u.id, u.emoji, u.name, whenLabel, inrPaise(u.amountPaise), u.isSubscription)
            },
        )
    }

    private fun analytics(d: Data, tx: List<Transaction>, month: String, today: String): MoneyAnalyticsUi {
        val title = monthTitle(month)
        val exp = tx.filter { it.kind == TxKind.Expense }
        val inc = income(tx)
        val sp = spent(tx)
        val net = inc - sp
        val tr = transferred(tx)
        val byCat = categoryBreakdown(tx, d.categories)
        val maxCat = byCat.firstOrNull()?.paise?.takeIf { it > 0 } ?: 1L
        val top = byCat.take(SERIES)
        val rest = byCat.drop(SERIES)
        val other = rest.sumOf { it.paise }
        val slices = top.mapIndexed { i, c -> DashSliceUi(c.name, c.paise, pct1(c.paise, sp), i) } +
            if (other > 0) listOf(DashSliceUi("Other (${rest.size})", other, pct1(other, sp), null)) else emptyList()
        val byAcc = accountUtilisation(tx, d.accounts)
        val outTotal = byAcc.sumOf { it.paise }
        val maxAcc = byAcc.firstOrNull()?.paise?.takeIf { it > 0 } ?: 1L
        val rate = savingsRatePct(inc, sp)
        return MoneyAnalyticsUi(
            empty = tx.isEmpty(),
            emptyTitle = "No transactions in $title",
            totalIncome = inrPaise(inc),
            totalExpenses = inrPaise(sp),
            expensesSub = "${exp.size} debits & charges",
            net = (if (net < 0) "−" else "") + inrPaise(net),
            netSub = if (tr > 0) "Income − expenses · ${inrPaise(tr)} moved between accounts" else "Income − expenses",
            savingsRate = rate?.let { (if (it < 0) "−" else "") + fixed1(abs(it)) + "%" } ?: "—",
            spentPaise = sp,
            donut = slices,
            ranking = byCat.mapIndexed { i, c ->
                DashRankUi(c.id, c.emoji, c.name, inrPaise(c.paise), pct1(c.paise, sp), c.count, (c.paise.toDouble() / maxCat).toFloat(), if (i < SERIES) i else null)
            },
            rankingCaption = "Total spend (₹) · $title",
            accountUse = byAcc.map { u ->
                DashAccountUseUi(u.account.id, u.account.name, u.account.mask, inrPaise(u.paise), pct1(u.paise, outTotal), u.count, (u.paise.toDouble() / maxAcc).toFloat(), u.account.kind == AccountKind.Credit)
            },
            topOutflows = topOutflows(tx, 5).mapIndexed { i, t ->
                val c = d.categories.firstOrNull { it.id == t.categoryId }
                DashOutflowUi(
                    id = t.id,
                    rank = i + 1,
                    title = t.merchant.ifEmpty { c?.name ?: "—" },
                    date = fmtDay(t.date, today, relative = false),
                    category = c?.let { "${it.emoji} ${it.name}" } ?: "Uncategorised",
                    account = d.accounts.firstOrNull { it.id == t.accountId }?.name ?: "",
                    amount = inrPaise(t.amountPaise),
                )
            },
        )
    }

    private companion object {
        /** Categorical series colours; slot 9+ folds into "Other". */
        const val SERIES = 8

        /** Web `pct1`: share with one decimal ("12.5%"); 0 without a total. */
        fun pct1(v: Long, total: Long): String = fixed1(if (total != 0L) v.toDouble() / total * 100 else 0.0) + "%"

        /** `toFixed(1)` for the non-negative percentages shown here. */
        fun fixed1(x: Double): String {
            val tenths = jsRound(x * 10)
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
