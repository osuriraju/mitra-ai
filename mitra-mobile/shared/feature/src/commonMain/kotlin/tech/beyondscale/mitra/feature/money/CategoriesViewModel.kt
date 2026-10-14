package tech.beyondscale.mitra.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.MON_LONG
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.money.spentByCategory
import tech.beyondscale.mitra.domain.money.thisMonth
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.core.inrPaise

data class CategoryRowUi(
    val id: String,
    val emoji: String,
    val name: String,
    /** "<n> tx · budget ₹X" */
    val subtitle: String,
    /** This month's amount. */
    val value: String,
    /** Whole rupees for the inline editor; empty without a budget. */
    val budgetRupees: String,
)

/** One legend/donut entry: [shade] 0..3 are accent shades, anything else is the track colour (web `shade(i)`). */
data class CategorySlice(val name: String, val paise: Long, val value: String, val pct: Int, val shade: Int)

data class CategoriesUiState(
    val loaded: Boolean = false,
    val kind: CategoryKind = CategoryKind.Expense,
    /** "October" */
    val monthLabel: String = "",
    val rows: List<CategoryRowUi> = emptyList(),
    /** Top 5 plus "Other"; empty when the month has no data. */
    val slices: List<CategorySlice> = emptyList(),
)

sealed interface CategoriesAction {
    data class SetKind(val kind: CategoryKind) : CategoriesAction
    /** [budgetRupees] is digits; ignored for income categories and when blank. */
    data class Create(val name: String, val emoji: String, val budgetRupees: String) : CategoriesAction
    data class Update(val id: String, val name: String, val budgetRupees: String) : CategoriesAction
    data class Delete(val id: String) : CategoriesAction
}

class CategoriesViewModel(private val money: MoneyRepository, private val clock: AppClock) : ViewModel() {
    private val queue = EventQueue<CategoriesAction>()
    val events: Flow<UiEvent<CategoriesAction>> = queue.events
    private val kind = MutableStateFlow(CategoryKind.Expense)

    val state: StateFlow<CategoriesUiState> = combine(money.categories, money.transactions, clock.dayFlow(), kind, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun onAction(a: CategoriesAction) {
        when (a) {
            is CategoriesAction.SetKind -> kind.value = a.kind
            is CategoriesAction.Create -> {
                val name = a.name.trim()
                if (name.isEmpty()) return
                val k = kind.value
                val budget = if (k == CategoryKind.Expense) rupeesToPaise(a.budgetRupees) else null
                viewModelScope.launch {
                    val c = money.addCategory(name, a.emoji, k, budget)
                    queue.send(UiEvent.Snack("Category created · ${c.emoji} ${c.name}"))
                }
            }
            is CategoriesAction.Update -> viewModelScope.launch {
                val current = money.categories.first().firstOrNull { it.id == a.id } ?: return@launch
                val changes = buildList {
                    val n = a.name.trim()
                    if (n.isNotEmpty() && n != current.name) add(CategoryChange.Name(n))
                    rupeesToPaise(a.budgetRupees)?.let { if (current.kind == CategoryKind.Expense) add(CategoryChange.Budget(it)) }
                }
                if (changes.isNotEmpty()) money.updateCategory(a.id, changes)
                queue.send(UiEvent.Snack("Category updated"))
            }
            is CategoriesAction.Delete -> viewModelScope.launch {
                money.deleteCategory(a.id)
                queue.send(UiEvent.Snack("Category deleted"))
            }
        }
    }

    private fun build(categories: List<Category>, all: List<Transaction>, today: String, kind: CategoryKind): CategoriesUiState {
        val tx = thisMonth(all, monthKey(today))
        val byCat: Map<String, Long> = if (kind == CategoryKind.Expense) spentByCategory(tx) else {
            val m = LinkedHashMap<String, Long>()
            tx.filter { it.kind == TxKind.Income && it.categoryId != null }.forEach { m[it.categoryId!!] = (m[it.categoryId!!] ?: 0L) + it.amountPaise }
            m
        }
        val cats = categories.filter { it.kind == kind && !it.archived }
            .map { c -> Triple(c, byCat[c.id] ?: 0L, tx.count { it.categoryId == c.id }) }
            .sortedByDescending { it.second }
        val total = cats.sumOf { it.second }
        val top = cats.take(5)
        val other = total - top.sumOf { it.second }
        fun pct(v: Long) = if (total > 0) jsRound(v * 100.0 / total).toInt() else 0
        val slices = if (total <= 0) emptyList() else
            top.mapIndexed { i, (c, v, _) -> CategorySlice(c.name, v, inrPaise(v), pct(v), i) } +
                if (other > 0) listOf(CategorySlice("Other", other, inrPaise(other), pct(other), 9)) else emptyList()
        return CategoriesUiState(
            loaded = true,
            kind = kind,
            monthLabel = MON_LONG[monthKey(today).substring(5, 7).toInt() - 1],
            rows = cats.map { (c, v, n) ->
                val b = c.budgetPaise
                CategoryRowUi(
                    id = c.id, emoji = c.emoji, name = c.name,
                    subtitle = "$n tx" + if (b != null && b > 0) " · budget ${inrPaise(b)}" else "",
                    value = inrPaise(v),
                    budgetRupees = if (b != null && b > 0) (b / 100).toString() else "",
                )
            },
            slices = slices,
        )
    }
}
