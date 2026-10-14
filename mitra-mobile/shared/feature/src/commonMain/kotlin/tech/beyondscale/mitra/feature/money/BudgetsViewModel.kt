package tech.beyondscale.mitra.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.MON
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.daysInMonth
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.inr
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.money.BudgetTone
import tech.beyondscale.mitra.domain.money.budgetPct
import tech.beyondscale.mitra.domain.money.budgetTone
import tech.beyondscale.mitra.domain.money.spentByCategory
import tech.beyondscale.mitra.domain.money.thisMonth
import tech.beyondscale.mitra.domain.money.totalBudget
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.math.max

data class BudgetCardUi(
    val id: String,
    val emoji: String,
    val name: String,
    /** "Monthly · resets 1 Nov" */
    val resets: String,
    /** "Over by ₹1,200" / "Near limit" / "On track" */
    val chip: String,
    val tone: BudgetTone,
    /** 0..1 for the bar. */
    val fraction: Float,
    /** "₹X of ₹Y" */
    val amounts: String,
    /** "₹Z left", or "—" when over. */
    val leftText: String,
    /** Whole rupees, prefilled in the edit field. */
    val budgetRupees: String,
)

data class CategoryOption(val id: String, val emoji: String, val name: String)

data class BudgetsUiState(
    val loaded: Boolean = false,
    val total: String = "",
    val spent: String = "",
    val left: String = "",
    /** "18 days" (web copy: `${left} days`). */
    val leftSub: String = "",
    val barFraction: Float = 0f,
    val barOver: Boolean = false,
    /** "Safe to spend: ₹1,240 / day until 31 Oct" */
    val safeLine: String = "",
    val cards: List<BudgetCardUi> = emptyList(),
    /** Expense categories without a budget (never the transfer category) that "New budget" can pick. */
    val unbudgeted: List<CategoryOption> = emptyList(),
)

sealed interface BudgetsAction {
    /** [rupees] is the digits typed in the field; blank or zero leaves the budget as it was. */
    data class Save(val id: String, val rupees: String) : BudgetsAction
    data class Remove(val id: String) : BudgetsAction
    data class Add(val id: String, val rupees: String) : BudgetsAction
}

/** Whole rupees typed by the user → paise; null when blank, zero or too large to be a budget. */
internal fun rupeesToPaise(text: String): Long? {
    val digits = text.filter { it in '0'..'9' }.trimStart('0')
    if (digits.isEmpty() || digits.length > 12) return null
    return digits.toLong() * 100
}

class BudgetsViewModel(private val money: tech.beyondscale.mitra.domain.repository.MoneyRepository, private val clock: AppClock) : ViewModel() {
    private val queue = EventQueue<BudgetsAction>()
    val events: Flow<UiEvent<BudgetsAction>> = queue.events

    val state: StateFlow<BudgetsUiState> = combine(money.categories, money.transactions, clock.dayFlow(), ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState())

    fun onAction(a: BudgetsAction) {
        when (a) {
            is BudgetsAction.Save -> viewModelScope.launch {
                rupeesToPaise(a.rupees)?.let { money.updateCategory(a.id, listOf(CategoryChange.Budget(it))) }
                queue.send(UiEvent.Snack("Budget updated"))
            }
            is BudgetsAction.Remove -> viewModelScope.launch {
                money.updateCategory(a.id, listOf(CategoryChange.Budget(null)))
                queue.send(UiEvent.Snack("Budget removed"))
            }
            is BudgetsAction.Add -> {
                val paise = rupeesToPaise(a.rupees) ?: return
                viewModelScope.launch {
                    money.updateCategory(a.id, listOf(CategoryChange.Budget(paise)))
                    queue.send(UiEvent.Snack("Budget set"))
                }
            }
        }
    }

    private fun build(categories: List<Category>, all: List<Transaction>, today: String): BudgetsUiState {
        val live = categories.filter { !it.archived }
        val month = monthKey(today)
        val byCat = spentByCategory(thisMonth(all, month))
        val budgeted = live.filter { it.kind == CategoryKind.Expense && (it.budgetPaise ?: 0L) > 0 }
        val total = totalBudget(categories)
        val spent = budgeted.sumOf { byCat[it.id] ?: 0L }
        val dim = daysInMonth(today)
        val daysLeft = dim - dayOfMonth(today) + 1
        val safe = max(0.0, (total - spent) / 100.0 / daysLeft)
        val nextMon = MON[monthKey(today).substring(5, 7).toInt() % 12]
        return BudgetsUiState(
            loaded = true,
            total = inrPaise(total),
            spent = inrPaise(spent),
            left = inrPaise(max(0L, total - spent)),
            leftSub = "$daysLeft days",
            barFraction = if (total > 0) (spent.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
            barOver = spent > total,
            safeLine = "Safe to spend: ${inr(safe)} / day until ${fmtShort("$month-${dim.toString().padStart(2, '0')}")}",
            cards = budgeted.map { c ->
                val b = c.budgetPaise ?: 0L
                val v = byCat[c.id] ?: 0L
                val tone = budgetTone(v, b)
                BudgetCardUi(
                    id = c.id, emoji = c.emoji, name = c.name,
                    resets = "Monthly · resets 1 $nextMon",
                    chip = when (tone) {
                        BudgetTone.Danger -> "Over by ${inrPaise(v - b)}"
                        BudgetTone.Warning -> "Near limit"
                        BudgetTone.Normal -> "On track"
                    },
                    tone = tone,
                    fraction = (budgetPct(v, b) / 100f).coerceIn(0f, 1f),
                    amounts = "${inrPaise(v)} of ${inrPaise(b)}",
                    leftText = if (tone == BudgetTone.Danger) "—" else "${inrPaise(b - v)} left",
                    budgetRupees = (b / 100).toString(),
                )
            },
            unbudgeted = live.filter { it.kind == CategoryKind.Expense && (it.budgetPaise ?: 0L) <= 0 && it.slug != "transfer" }
                .map { CategoryOption(it.id, it.emoji, it.name) },
        )
    }
}
