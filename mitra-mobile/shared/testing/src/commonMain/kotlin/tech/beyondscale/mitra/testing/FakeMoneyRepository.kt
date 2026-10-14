package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import tech.beyondscale.mitra.domain.money.paidThisMonth
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PayResult

/** In-memory [MoneyRepository]: writes apply locally (balances move like the real repository) and are recorded in [calls]. */
class FakeMoneyRepository(
    accounts: List<Account> = emptyList(),
    categories: List<Category> = emptyList(),
    transactions: List<Transaction> = emptyList(),
    recurring: List<Recurring> = emptyList(),
    subscriptions: List<Subscription> = emptyList(),
    private val today: String = "2026-10-14",
) : MoneyRepository {
    val accountFlow = MutableStateFlow(accounts)
    val categoryFlow = MutableStateFlow(categories)
    val transactionFlow = MutableStateFlow(transactions)
    val recurringFlow = MutableStateFlow(recurring)
    val subscriptionFlow = MutableStateFlow(subscriptions)
    override val accounts: Flow<List<Account>> = accountFlow
    override val categories: Flow<List<Category>> = categoryFlow
    override val transactions: Flow<List<Transaction>> = transactionFlow
    override val recurring: Flow<List<Recurring>> = recurringFlow
    override val subscriptions: Flow<List<Subscription>> = subscriptionFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    /** When set, `payRecurring` records the call and returns it without changing anything. */
    var payResult: PayResult? = null
    private var txSeq = 0
    private var catSeq = 0
    private var recSeq = 0
    private var subSeq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun addTransaction(draft: TxDraft): Transaction {
        val tx = draft.toTransaction("tx${++txSeq}")
        calls += "addTransaction"
        insert(tx)
        return tx
    }

    override suspend fun updateTransaction(id: String, draft: TxDraft) {
        calls += "updateTransaction:$id"
        val old = transactionFlow.value.firstOrNull { it.id == id } ?: return
        val next = draft.toTransaction(id).copy(source = old.source, recurringId = old.recurringId, tags = old.tags)
        applyBalance(old, -1)
        applyBalance(next, 1)
        transactionFlow.update { list -> list.map { if (it.id == id) next else it } }
    }

    override suspend fun deleteTransaction(id: String): Transaction? {
        calls += "deleteTransaction:$id"
        val tx = transactionFlow.value.firstOrNull { it.id == id } ?: return null
        applyBalance(tx, -1)
        transactionFlow.update { list -> list.filter { it.id != id } }
        return tx
    }

    override suspend fun restoreTransaction(tx: Transaction) {
        calls += "restoreTransaction:${tx.id}"
        insert(tx)
    }

    override suspend fun addCategory(name: String, emoji: String, kind: CategoryKind, budgetPaise: Long?): Category {
        val c = Category("cat${++catSeq}", name.trim(), emoji, kind, budgetPaise = budgetPaise)
        calls += "addCategory:${c.name}"
        categoryFlow.update { it + c }
        return c
    }

    override suspend fun updateCategory(id: String, changes: List<CategoryChange>) {
        calls += "updateCategory:$id:" + changes.joinToString(",")
        categoryFlow.update { list ->
            list.map { c ->
                if (c.id != id) c else changes.fold(c) { acc, ch ->
                    when (ch) {
                        is CategoryChange.Name -> acc.copy(name = ch.v)
                        is CategoryChange.Emoji -> acc.copy(emoji = ch.v)
                        is CategoryChange.Budget -> acc.copy(budgetPaise = ch.paise)
                    }
                }
            }
        }
    }

    override suspend fun deleteCategory(id: String) {
        calls += "deleteCategory:$id"
        categoryFlow.update { list -> list.filter { it.id != id } }
        transactionFlow.update { list -> list.map { if (it.categoryId == id) it.copy(categoryId = null) else it } }
    }

    override suspend fun addRecurring(draft: RecurringDraft): Recurring {
        val r = Recurring(
            id = "rec${++recSeq}", name = draft.name.trim(), emoji = draft.emoji, amountPaise = draft.amountPaise,
            day = draft.day.coerceIn(1, 28), method = "UPI", kind = draft.kind, reminder = draft.reminder,
            categoryId = draft.categoryId, accountId = draft.accountId, toAccountId = draft.toAccountId, lastPaidOn = null,
        )
        calls += "addRecurring:${r.name}"
        recurringFlow.update { it + r }
        return r
    }

    override suspend fun setRecurringReminder(id: String, on: Boolean) {
        calls += "reminder:$id:$on"
        recurringFlow.update { list -> list.map { if (it.id == id) it.copy(reminder = on) else it } }
    }

    override suspend fun deleteRecurring(id: String) {
        calls += "deleteRecurring:$id"
        recurringFlow.update { list -> list.filter { it.id != id } }
        transactionFlow.update { list -> list.map { if (it.recurringId == id) it.copy(recurringId = null) else it } }
    }

    override suspend fun payRecurring(id: String): PayResult {
        calls += "pay:$id"
        payResult?.let { return it }
        val r = recurringFlow.value.firstOrNull { it.id == id } ?: return PayResult.NotFound
        if (paidThisMonth(r, today)) return PayResult.AlreadyPaid
        val accountId = r.accountId ?: accountFlow.value.firstOrNull { !it.archived }?.id ?: return PayResult.NoAccount
        val kind = when (r.kind) {
            RecurringKind.Income -> TxKind.Income
            RecurringKind.Transfer -> TxKind.Transfer
            RecurringKind.Bill -> TxKind.Expense
        }
        if (kind == TxKind.Transfer && r.toAccountId == null) return PayResult.NoDestination
        val tx = Transaction(
            id = "tx${++txSeq}", kind = kind, amountPaise = r.amountPaise, categoryId = r.categoryId, accountId = accountId,
            toAccountId = if (kind == TxKind.Transfer) r.toAccountId else null, merchant = r.name, note = null,
            date = today, time = "09:00", source = TxSource.Recurring, recurringId = r.id,
        )
        insert(tx)
        recurringFlow.update { list -> list.map { if (it.id == id) it.copy(lastPaidOn = today) else it } }
        return PayResult.Paid(tx)
    }

    override suspend fun addSubscription(name: String, amountPaise: Long, cycle: Cycle): Subscription {
        val n = name.trim()
        val s = Subscription(
            id = "sub${++subSeq}", name = n, amountPaise = amountPaise, cycle = cycle,
            next = addDays(today, if (cycle == Cycle.Yearly) 365 else 30), color = "#6366F1",
            letter = n.take(1).uppercase(), lastUsed = null, categoryId = null, accountId = null,
        )
        calls += "addSubscription:$n"
        subscriptionFlow.update { it + s }
        return s
    }

    override suspend fun markSubscriptionUsed(id: String) {
        calls += "used:$id"
        subscriptionFlow.update { list -> list.map { if (it.id == id) it.copy(lastUsed = today) else it } }
    }

    override suspend fun deleteSubscription(id: String) {
        calls += "deleteSubscription:$id"
        subscriptionFlow.update { list -> list.filter { it.id != id } }
    }

    private fun insert(tx: Transaction) {
        applyBalance(tx, 1)
        transactionFlow.update { listOf(tx) + it }
    }

    /** The web store's balance effect: the source account moves by ±amount, a transfer's destination gains it. */
    private fun applyBalance(tx: Transaction, sign: Int) {
        accountFlow.update { list ->
            list.map { a ->
                when {
                    a.id == tx.accountId -> a.copy(balancePaise = a.balancePaise + sign * (if (tx.kind == TxKind.Income) tx.amountPaise else -tx.amountPaise))
                    a.id == tx.toAccountId && tx.kind == TxKind.Transfer -> a.copy(balancePaise = a.balancePaise + sign * tx.amountPaise)
                    else -> a
                }
            }
        }
    }

    private fun TxDraft.toTransaction(id: String) = Transaction(
        id = id, kind = kind, amountPaise = amountPaise, categoryId = categoryId, accountId = accountId,
        toAccountId = if (kind == TxKind.Transfer) toAccountId else null, merchant = merchant.trim(), note = note,
        date = date, time = time, source = source,
    )
}
