package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft

/**
 * Offline-first money: every write lands in the local DB immediately (moving account balances like the web store)
 * and reaches the server through the outbox.
 */
interface MoneyRepository {
    val accounts: Flow<List<Account>>
    val categories: Flow<List<Category>>
    /** Newest first. */
    val transactions: Flow<List<Transaction>>
    val recurring: Flow<List<Recurring>>
    val subscriptions: Flow<List<Subscription>>

    /** Re-reads `money/bootstrap`; records with queued changes are kept. See [TasksRepository.refresh] for [REFRESH_SUPERSEDED]. */
    suspend fun refresh(): Outcome<Unit>
    suspend fun addTransaction(draft: TxDraft): Transaction
    suspend fun updateTransaction(id: String, draft: TxDraft)
    /** Removes the transaction and reverses its balance effect; returns it so the caller can offer Undo. */
    suspend fun deleteTransaction(id: String): Transaction?
    /** Undo for [deleteTransaction]: re-creates it with the same id. */
    suspend fun restoreTransaction(tx: Transaction)
    suspend fun addCategory(name: String, emoji: String, kind: CategoryKind, budgetPaise: Long? = null): Category
    suspend fun updateCategory(id: String, changes: List<CategoryChange>)
    /** Deletes the category; its transactions become uncategorised (as on the server). */
    suspend fun deleteCategory(id: String)
    suspend fun addRecurring(draft: RecurringDraft): Recurring
    suspend fun setRecurringReminder(id: String, on: Boolean)
    suspend fun deleteRecurring(id: String)
    /** "Mark paid": records this month's transaction for the bill, dated the device's local day. */
    suspend fun payRecurring(id: String): PayResult
    suspend fun addSubscription(name: String, amountPaise: Long, cycle: Cycle): Subscription
    /** Sets `lastUsed` to the device's local day. */
    suspend fun markSubscriptionUsed(id: String)
    suspend fun deleteSubscription(id: String)

    companion object {
        const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED
    }
}

sealed interface PayResult {
    data class Paid(val transaction: Transaction) : PayResult
    /** Already paid this month (or a payment is still queued) — a second tap is a no-op. */
    data object AlreadyPaid : PayResult
    data object NoAccount : PayResult
    data object NoDestination : PayResult
    data object NotFound : PayResult
}
