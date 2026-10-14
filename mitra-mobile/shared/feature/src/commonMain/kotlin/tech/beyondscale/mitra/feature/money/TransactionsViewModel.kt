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
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.core.monthTitle
import tech.beyondscale.mitra.core.signedInrPaise
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.money.TxFilter
import tech.beyondscale.mitra.domain.money.TxSort
import tech.beyondscale.mitra.domain.money.filterTransactions
import tech.beyondscale.mitra.domain.money.groupTransactions
import tech.beyondscale.mitra.domain.money.income
import tech.beyondscale.mitra.domain.money.monthsWithData
import tech.beyondscale.mitra.domain.money.spent
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

/** A filter choice; `id == null` is "All …". */
data class TxFilterOption(val id: String?, val label: String)

data class TxGroupUi(val title: String, val net: String, val rows: List<TxRowUi>)

data class TransactionsUiState(
    val loaded: Boolean = false,
    /** Null = all time. */
    val month: String? = null,
    val monthLabel: String = "All time",
    val monthOptions: List<TxFilterOption> = emptyList(),
    val categoryId: String? = null,
    val categoryLabel: String = "All categories",
    val categoryOptions: List<TxFilterOption> = emptyList(),
    val accountId: String? = null,
    val accountLabel: String = "All accounts",
    val accountOptions: List<TxFilterOption> = emptyList(),
    val kind: TxKind? = null,
    val kindLabel: String = "All types",
    val query: String = "",
    /** The web's "Clear" chip: any category/account/type filter or a query. */
    val showClear: Boolean = false,
    val summary: String = "",
    val sortLabel: String = "Sort by amount",
    val groups: List<TxGroupUi> = emptyList(),
    val empty: Boolean = false,
    val refreshing: Boolean = false,
)

sealed interface TransactionsAction {
    /** Null = all time. */
    data class Month(val key: String?) : TransactionsAction
    data class Category(val id: String?) : TransactionsAction
    data class Account(val id: String?) : TransactionsAction
    data class Kind(val k: TxKind?) : TransactionsAction
    data class Query(val q: String) : TransactionsAction
    data object ToggleSort : TransactionsAction
    /** The empty state's "Clear filters": everything off, month set to all time. */
    data object ClearFilters : TransactionsAction
    data class Delete(val id: String) : TransactionsAction
    data class UndoDelete(val tx: Transaction) : TransactionsAction
    data object Refresh : TransactionsAction
}

/** Port of `TransactionsScreen` (mitra-frontend/src/screens/money.tsx). */
class TransactionsViewModel(private val money: MoneyRepository, clock: AppClock) : ViewModel() {
    private data class Local(
        /** [FOLLOW_CURRENT] tracks the current month across local midnight; null = all time. */
        val month: String? = FOLLOW_CURRENT,
        val categoryId: String? = null,
        val accountId: String? = null,
        val kind: TxKind? = null,
        val query: String = "",
        val sort: TxSort = TxSort.Date,
        val refreshing: Boolean = false,
    )

    private data class Data(val accounts: List<Account>, val categories: List<Category>, val transactions: List<Transaction>)

    private val local = MutableStateFlow(Local())
    private var started = false
    private val queue = EventQueue<TransactionsAction>()
    val events: Flow<UiEvent<TransactionsAction>> = queue.events

    private val data = combine(money.accounts, money.categories, money.transactions, ::Data)

    val state: StateFlow<TransactionsUiState> = combine(data, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    /** Applies the route's category (Categories → "Transactions") once, so it never overrides a later choice. */
    fun start(initialCategoryId: String?) {
        if (started) return
        started = true
        if (!initialCategoryId.isNullOrEmpty()) local.update { it.copy(categoryId = initialCategoryId) }
    }

    fun onAction(a: TransactionsAction) {
        when (a) {
            is TransactionsAction.Month -> local.update { it.copy(month = a.key) }
            is TransactionsAction.Category -> local.update { it.copy(categoryId = a.id) }
            is TransactionsAction.Account -> local.update { it.copy(accountId = a.id) }
            is TransactionsAction.Kind -> local.update { it.copy(kind = a.k) }
            is TransactionsAction.Query -> local.update { it.copy(query = a.q) }
            TransactionsAction.ToggleSort -> local.update { it.copy(sort = if (it.sort == TxSort.Date) TxSort.Amount else TxSort.Date) }
            TransactionsAction.ClearFilters -> local.update { it.copy(month = null, categoryId = null, accountId = null, kind = null, query = "") }
            is TransactionsAction.Delete -> viewModelScope.launch {
                val tx = money.deleteTransaction(a.id) ?: return@launch
                queue.send(UiEvent.Snack("Transaction deleted", TransactionsAction.UndoDelete(tx)))
            }
            is TransactionsAction.UndoDelete -> viewModelScope.launch { money.restoreTransaction(a.tx) }
            TransactionsAction.Refresh -> refresh()
        }
    }

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val result = money.refresh()
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

    private fun build(d: Data, today: String, l: Local): TransactionsUiState {
        val month = if (l.month == FOLLOW_CURRENT) monthKey(today) else l.month
        // A deleted (or archived) category/account stops filtering, so the chip never reads "All …" over an empty list.
        val category = d.categories.firstOrNull { it.id == l.categoryId }
        val account = d.accounts.firstOrNull { it.id == l.accountId }
        val filter = TxFilter(month = month, categoryId = category?.id, accountId = account?.id, kind = l.kind, query = l.query)
        val list = filterTransactions(d.transactions, filter, l.sort)
        val n = list.size
        return TransactionsUiState(
            loaded = true,
            month = month,
            monthLabel = month?.let(::monthTitle) ?: "All time",
            monthOptions = listOf(TxFilterOption(null, "All time")) + monthsWithData(d.transactions, today).map { TxFilterOption(it, monthTitle(it)) },
            categoryId = category?.id,
            categoryLabel = category?.let { "${it.emoji} ${it.name}" } ?: "All categories",
            categoryOptions = listOf(TxFilterOption(null, "All categories")) + d.categories.map { TxFilterOption(it.id, "${it.emoji} ${it.name}") },
            accountId = account?.id,
            accountLabel = account?.let { "${it.name} ${it.mask}".trim() } ?: "All accounts",
            accountOptions = listOf(TxFilterOption(null, "All accounts")) + d.accounts.map { TxFilterOption(it.id, "${it.name} ${it.mask}".trim()) },
            kind = l.kind,
            kindLabel = l.kind?.let(::kindName) ?: "All types",
            query = l.query,
            showClear = category != null || account != null || l.kind != null || l.query.isNotEmpty(),
            summary = "$n transaction${if (n == 1) "" else "s"} · spent ${inrPaise(spent(list))} · income ${inrPaise(income(list))}",
            sortLabel = if (l.sort == TxSort.Date) "Sort by amount" else "Sort by date",
            groups = groupTransactions(list, today).map { g ->
                TxGroupUi(g.title, signedInrPaise(g.netPaise), g.items.map { txRowUi(it, d.categories, d.accounts, today) })
            },
            empty = list.isEmpty(),
            refreshing = l.refreshing,
        )
    }

    private companion object {
        const val FOLLOW_CURRENT = "current"
    }
}

/** "Expense" · "Income" · "Transfer". */
internal fun kindName(k: TxKind): String = when (k) {
    TxKind.Expense -> "Expense"
    TxKind.Income -> "Income"
    TxKind.Transfer -> "Transfer"
}
