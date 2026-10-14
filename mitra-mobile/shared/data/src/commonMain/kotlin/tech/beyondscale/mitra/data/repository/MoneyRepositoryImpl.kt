package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.addMonthsJs
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.CategoryDto
import tech.beyondscale.mitra.data.remote.MoneyBootstrapDto
import tech.beyondscale.mitra.data.remote.PayResultDto
import tech.beyondscale.mitra.data.remote.RecurringDto
import tech.beyondscale.mitra.data.remote.SubscriptionDto
import tech.beyondscale.mitra.data.remote.TransactionDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.patchBody
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import tech.beyondscale.mitra.domain.money.paidThisMonth
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PayResult

class MoneyRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MoneyRepository {
    /** Bumped before every local write and every applied server echo — see `TasksRepositoryImpl.writes`. */
    private val writes = MutableStateFlow(0L)

    override val accounts: Flow<List<Account>> = db.accountsQueries.selectAll().asFlow().mapToList(io)
        .map { rows -> rows.map { it.toDomain() }.filterNot { it.archived } }.shareForRepository(scope)
    override val categories: Flow<List<Category>> = db.categoriesQueries.selectAll().asFlow().mapToList(io)
        .map { rows -> rows.map { it.toDomain() }.filterNot { it.archived } }.shareForRepository(scope)
    override val transactions: Flow<List<Transaction>> = db.transactionsQueries.selectAll().asFlow().mapToList(io)
        .map { rows -> rows.map { it.toDomain() } }.shareForRepository(scope)
    override val recurring: Flow<List<Recurring>> = db.recurringQueries.selectAll().asFlow().mapToList(io)
        .map { rows -> rows.map { it.toDomain() } }.shareForRepository(scope)
    override val subscriptions: Flow<List<Subscription>> = db.subscriptionsQueries.selectAll().asFlow().mapToList(io)
        .map { rows -> rows.map { it.toDomain() } }.shareForRepository(scope)

    init {
        engine.register(TRANSACTION, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(TransactionDto.serializer(), response) ?: return // DELETE answers { ok: true }
                applyEcho(TRANSACTION, dto.id) {
                    // Deleted locally while the write was in flight: don't resurrect it. Balances already moved locally
                    // when the write was made; the echo never moves them again.
                    if (db.transactionsQueries.selectById(dto.id).executeAsOneOrNull() != null) {
                        db.transactionsQueries.upsert(dto.toDomain().toRow())
                    }
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(CATEGORY, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(CategoryDto.serializer(), response) ?: return
                applyEcho(CATEGORY, dto.id) {
                    val local = db.categoriesQueries.selectById(dto.id).executeAsOneOrNull() ?: return@applyEcho
                    db.categoriesQueries.upsert(dto.toDomain().toRow(local.position))
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(RECURRING, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(RecurringDto.serializer(), response) ?: return
                // A queued "Mark paid" also changes this row (lastPaidOn): wait for its own answer.
                applyEcho(RECURRING, dto.id) { if (dto.id !in pendingIds(RECURRING_PAY)) echoRecurring(dto) }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(SUBSCRIPTION, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(SubscriptionDto.serializer(), response) ?: return
                applyEcho(SUBSCRIPTION, dto.id) {
                    if (db.subscriptionsQueries.selectById(dto.id).executeAsOneOrNull() != null) {
                        db.subscriptionsQueries.upsert(dto.toDomain().toRow())
                    }
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(RECURRING_PAY, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(PayResultDto.serializer(), response) ?: return
                writes.update { it + 1 }
                withContext(io) {
                    db.transaction {
                        val tx = dto.transaction
                        val local = selectTransaction(tx.id)
                        // Never resurrect, never move balances (moved when it was paid). The server stamps the time with
                        // its own clock; the device's local time is kept.
                        if (local != null && tx.id !in pendingIds(TRANSACTION)) {
                            db.transactionsQueries.upsert(tx.toDomain().copy(time = local.time).toRow())
                        }
                        if (dto.recurring.id !in pendingIds(RECURRING) + pendingIds(RECURRING_PAY)) echoRecurring(dto.recurring)
                    }
                }
            }
            // e.g. 400 NO_ACCOUNT, or the third `500 INTERNAL` of a resend whose transaction id the server already has
            // (a duplicate id is an unhandled error there, indistinguishable from any other): the server copy decides.
            override suspend fun onRejected(error: AppError) { refresh() }
        })
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<MoneyBootstrapDto>(HttpMethod.Get, "money/bootstrap")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val today = clock.today()
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { applyBootstrap(body, today); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        // Still racing writes: applied nothing. ModuleRefresherImpl refreshes once more after the drain that follows.
        return Outcome.Err(AppError.Server(409, MoneyRepository.REFRESH_SUPERSEDED, "Your changes are still syncing. Try again in a moment."))
    }

    /** Upsert everything the server has, delete what it no longer has — except records with queued changes. */
    private fun applyBootstrap(body: MoneyBootstrapDto, today: String) {
        val pendingTx = pendingIds(TRANSACTION)
        val pendingPay = pendingIds(RECURRING_PAY)
        val pendingCategories = pendingIds(CATEGORY)
        val pendingRecurring = pendingIds(RECURRING) + pendingPay
        val pendingSubscriptions = pendingIds(SUBSCRIPTION)

        // Accounts (read-only on the device). The server's balance lacks the effects of queued transactions and
        // payments, so while any is queued the local balance (which has them) is kept.
        val keepBalances = pendingTx.isNotEmpty() || pendingPay.isNotEmpty()
        val serverAccounts = body.accounts.map { it.id }.toSet()
        // An account the server no longer lists (archived on the web) stays while queued writes may still use it, so
        // a pending transaction never points at an account the device has dropped; the next refresh removes it.
        val stillUsed = if (keepBalances) {
            db.transactionsQueries.selectAll().executeAsList().flatMap { listOfNotNull(it.accountId, it.toAccountId) }.toSet()
        } else {
            emptySet()
        }
        db.accountsQueries.selectIds().executeAsList().filter { it !in serverAccounts && it !in stillUsed }.forEach { db.accountsQueries.delete(it) }
        body.accounts.forEachIndexed { i, dto ->
            val server = dto.toDomain()
            val local = if (keepBalances) db.accountsQueries.selectById(dto.id).executeAsOneOrNull() else null
            db.accountsQueries.upsert(server.copy(balancePaise = local?.balancePaise ?: server.balancePaise).toRow(i.toLong()))
        }

        // Categories.
        val localCategories = db.categoriesQueries.selectIds().executeAsList().toSet()
        val serverCategories = body.categories.map { it.id }.toSet()
        localCategories.filter { it !in serverCategories && it !in pendingCategories }.forEach { db.categoriesQueries.delete(it) }
        body.categories.forEachIndexed { i, dto ->
            if (dto.id !in pendingCategories) db.categoriesQueries.upsert(dto.toDomain().toRow(i.toLong()))
        }

        // Transactions. The server sends the last 12 months by its UTC date: a local row older than that (plus a
        // margin for the time-zone difference) missing from the answer is simply outside it, never deleted. Every
        // row the server does send is taken (e.g. an old one whose date was moved into the window on the web).
        val windowStart = addDays(addMonthsJs(today, -12), WINDOW_MARGIN_DAYS)
        val inWindow = db.transactionsQueries.selectIdsOnOrAfter(windowStart).executeAsList().toSet()
        // A category whose DELETE is still queued: the server uncategorises its transactions once it lands; do so now.
        val deletedCategories = pendingCategories - db.categoriesQueries.selectIds().executeAsList().toSet()
        val incoming = body.transactions.map { it.toDomain() }
            .map { if (it.categoryId != null && it.categoryId in deletedCategories) it.copy(categoryId = null) else it }
        val serverTx = incoming.map { it.id }.toSet()
        inWindow.filter { it !in serverTx && it !in pendingTx }.forEach { db.transactionsQueries.delete(it) }
        incoming.filter { it.id !in pendingTx }.forEach { db.transactionsQueries.upsert(it.toRow()) }

        // Recurring (a queued "Mark paid" counts as a pending change to its bill).
        val serverRecurring = body.recurring.map { it.id }.toSet()
        db.recurringQueries.selectIds().executeAsList().filter { it !in serverRecurring && it !in pendingRecurring }
            .forEach { db.recurringQueries.delete(it) }
        body.recurring.filter { it.id !in pendingRecurring }.forEach { db.recurringQueries.upsert(it.toDomain().toRow()) }

        // Subscriptions.
        val serverSubscriptions = body.subscriptions.map { it.id }.toSet()
        db.subscriptionsQueries.selectIds().executeAsList().filter { it !in serverSubscriptions && it !in pendingSubscriptions }
            .forEach { db.subscriptionsQueries.delete(it) }
        body.subscriptions.filter { it.id !in pendingSubscriptions }.forEach { db.subscriptionsQueries.upsert(it.toDomain().toRow()) }
    }

    /* ---------- transaction writes ---------- */

    override suspend fun addTransaction(draft: TxDraft): Transaction {
        val tx = draft.toTransaction(newId())
        write { insertTransaction(tx) }
        return tx
    }

    override suspend fun updateTransaction(id: String, draft: TxDraft) {
        write {
            val old = selectTransaction(id) ?: return@write
            val next = old.edited(draft)
            db.applyEffects(balanceEffects(old), -1) // undo the old movement…
            db.transactionsQueries.upsert(next.toRow())
            db.applyEffects(balanceEffects(next), 1) // …then apply the new one
            outbox.enqueue(TRANSACTION, "PATCH", "money/transactions/$id", next.patchBody(), coalesceKey = "transaction:$id", entityId = id)
        }
    }

    override suspend fun deleteTransaction(id: String): Transaction? {
        var removed: Transaction? = null
        write {
            val old = selectTransaction(id) ?: return@write
            db.applyEffects(balanceEffects(old), -1)
            db.transactionsQueries.delete(id)
            outbox.enqueue(TRANSACTION, "DELETE", "money/transactions/$id", null, entityId = id)
            removed = old
        }
        return removed
    }

    override suspend fun restoreTransaction(tx: Transaction) {
        write { insertTransaction(tx) }
    }

    /* ---------- categories ---------- */

    override suspend fun addCategory(name: String, emoji: String, kind: CategoryKind, budgetPaise: Long?): Category {
        val c = Category(newId(), name.trim(), emoji, kind, slug = null, budgetPaise = budgetPaise)
        write {
            db.categoriesQueries.upsert(c.toRow(db.categoriesQueries.nextPosition().executeAsOne()))
            outbox.enqueue(CATEGORY, "POST", "money/categories", c.createBody(), entityId = c.id)
        }
        return c
    }

    override suspend fun updateCategory(id: String, changes: List<CategoryChange>) {
        if (changes.isEmpty()) return
        write {
            val row = db.categoriesQueries.selectById(id).executeAsOneOrNull() ?: return@write
            db.categoriesQueries.upsert(row.toDomain().applied(changes).toRow(row.position))
            outbox.enqueue(CATEGORY, "PATCH", "money/categories/$id", categoryPatch(changes), coalesceKey = "category:$id", entityId = id)
        }
    }

    override suspend fun deleteCategory(id: String) {
        write {
            val c = db.categoriesQueries.selectById(id).executeAsOneOrNull()?.toDomain() ?: return@write
            db.categoriesQueries.delete(id)
            // The server archives the transfer category (transfers need it) and keeps every reference; any other
            // category is deleted and its references become null (FK SET NULL).
            if (c.slug != TRANSFER_SLUG) {
                db.transactionsQueries.clearCategory(id)
                db.recurringQueries.selectAll().executeAsList().map { it.toDomain() }.filter { it.categoryId == id }
                    .forEach { db.recurringQueries.upsert(it.copy(categoryId = null).toRow()) }
                db.subscriptionsQueries.selectAll().executeAsList().map { it.toDomain() }.filter { it.categoryId == id }
                    .forEach { db.subscriptionsQueries.upsert(it.copy(categoryId = null).toRow()) }
            }
            outbox.enqueue(CATEGORY, "DELETE", "money/categories/$id", null, entityId = id)
        }
    }

    /* ---------- recurring ---------- */

    override suspend fun addRecurring(draft: RecurringDraft): Recurring {
        val r = Recurring(
            id = newId(), name = draft.name.trim(), emoji = draft.emoji, amountPaise = draft.amountPaise,
            day = draft.day.coerceIn(1, 28), method = "UPI", kind = draft.kind, reminder = draft.reminder,
            categoryId = draft.categoryId?.ifEmpty { null }, accountId = draft.accountId?.ifEmpty { null },
            toAccountId = if (draft.kind == RecurringKind.Transfer) draft.toAccountId?.ifEmpty { null } else null, lastPaidOn = null,
        )
        write {
            db.recurringQueries.upsert(r.toRow())
            outbox.enqueue(RECURRING, "POST", "money/recurring", r.createBody(), entityId = r.id)
        }
        return r
    }

    override suspend fun setRecurringReminder(id: String, on: Boolean) {
        write {
            val r = selectRecurring(id) ?: return@write
            if (r.reminder == on) return@write
            db.recurringQueries.upsert(r.copy(reminder = on).toRow())
            outbox.enqueue(RECURRING, "PATCH", "money/recurring/$id", buildJsonObject { put("reminder", on) }, coalesceKey = "recurring:$id", entityId = id)
        }
    }

    override suspend fun deleteRecurring(id: String) {
        write {
            if (selectRecurring(id) == null) return@write
            db.recurringQueries.delete(id)
            // Past payments stay; the server unlinks them (FK SET NULL).
            db.transactionsQueries.selectAll().executeAsList().map { it.toDomain() }.filter { it.recurringId == id }
                .forEach { db.transactionsQueries.upsert(it.copy(recurringId = null).toRow()) }
            outbox.enqueue(RECURRING, "DELETE", "money/recurring/$id", null, entityId = id)
        }
    }

    override suspend fun payRecurring(id: String): PayResult {
        val now = clock.localNow()
        val today = now.date.toString()
        val time = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
        var result: PayResult = PayResult.NotFound
        // Checks and writes in one transaction: a second tap sees the first one's lastPaidOn and queued row.
        write {
            val r = selectRecurring(id) ?: return@write
            if (paidThisMonth(r, today) || id in pendingIds(RECURRING_PAY)) { result = PayResult.AlreadyPaid; return@write }
            val accountId = r.accountId ?: db.accountsQueries.selectAll().executeAsList().firstOrNull { it.archived == 0L }?.id
            if (accountId == null) { result = PayResult.NoAccount; return@write }
            val kind = when (r.kind) { RecurringKind.Income -> TxKind.Income; RecurringKind.Transfer -> TxKind.Transfer; RecurringKind.Bill -> TxKind.Expense }
            if (kind == TxKind.Transfer && r.toAccountId == null) { result = PayResult.NoDestination; return@write }
            // Same fallback as the server's `pay`.
            val fallbackSlug = when (r.kind) { RecurringKind.Transfer -> TRANSFER_SLUG; RecurringKind.Income -> "salary"; RecurringKind.Bill -> "home" }
            val categoryId = r.categoryId ?: db.categoriesQueries.selectAll().executeAsList().firstOrNull { it.slug == fallbackSlug }?.id
            val tx = Transaction(
                id = newId(), kind = kind, amountPaise = r.amountPaise, categoryId = categoryId, accountId = accountId,
                toAccountId = if (kind == TxKind.Transfer) r.toAccountId else null, merchant = r.name, note = null,
                date = today, time = time, source = TxSource.Recurring, recurringId = r.id,
            )
            db.transactionsQueries.upsert(tx.toRow())
            db.applyEffects(balanceEffects(tx), 1)
            db.recurringQueries.setLastPaidOn(today, r.id)
            // The client id makes a resend hit the same transaction; the local date beats the server's UTC day.
            val body = buildJsonObject { put("id", tx.id); put("accountId", accountId); put("date", today) }
            outbox.enqueue(RECURRING_PAY, "POST", "money/recurring/${r.id}/pay", body, entityId = r.id)
            result = PayResult.Paid(tx)
        }
        return result
    }

    /* ---------- subscriptions ---------- */

    override suspend fun addSubscription(name: String, amountPaise: Long, cycle: Cycle): Subscription {
        val today = clock.today()
        val trimmed = name.trim()
        val s = Subscription(
            id = newId(), name = trimmed, amountPaise = amountPaise, cycle = cycle,
            next = addDays(today, if (cycle == Cycle.Yearly) 365 else 30), color = DEFAULT_SUBSCRIPTION_COLOR,
            letter = trimmed.take(1).uppercase(), lastUsed = null, categoryId = null, accountId = null,
        )
        write {
            db.subscriptionsQueries.upsert(s.toRow())
            outbox.enqueue(SUBSCRIPTION, "POST", "money/subscriptions", s.createBody(), entityId = s.id)
        }
        return s
    }

    override suspend fun markSubscriptionUsed(id: String) {
        val today = clock.today()
        write {
            val s = db.subscriptionsQueries.selectById(id).executeAsOneOrNull()?.toDomain() ?: return@write
            db.subscriptionsQueries.upsert(s.copy(lastUsed = today).toRow())
            outbox.enqueue(SUBSCRIPTION, "PATCH", "money/subscriptions/$id", buildJsonObject { put("lastUsed", today) }, coalesceKey = "subscription:$id", entityId = id)
        }
    }

    override suspend fun deleteSubscription(id: String) {
        write {
            if (db.subscriptionsQueries.selectById(id).executeAsOneOrNull() == null) return@write
            db.subscriptionsQueries.delete(id)
            outbox.enqueue(SUBSCRIPTION, "DELETE", "money/subscriptions/$id", null, entityId = id)
        }
    }

    /* ---------- helpers ---------- */

    /** Local change + outbox row in one transaction. The counter is bumped first (see [writes]). */
    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    private fun selectRecurring(id: String): Recurring? = db.recurringQueries.selectById(id).executeAsOneOrNull()?.toDomain()

    /** Echo of a recurring row: never resurrects one deleted locally. Call inside a transaction. */
    private fun echoRecurring(dto: RecurringDto) {
        if (selectRecurring(dto.id) != null) db.recurringQueries.upsert(dto.toDomain().toRow())
    }

    private fun selectTransaction(id: String): Transaction? = db.transactionsQueries.selectById(id).executeAsOneOrNull()?.toDomain()

    /** New (or restored) row, its balance effects and its POST. */
    private fun insertTransaction(tx: Transaction) {
        db.transactionsQueries.upsert(tx.toRow())
        db.applyEffects(balanceEffects(tx), 1)
        outbox.enqueue(TRANSACTION, "POST", "money/transactions", tx.createBody(), entityId = tx.id)
    }

    private fun pendingIds(entity: String): Set<String> =
        db.outboxQueries.pendingEntityIds(entity).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement?): T? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    /** Server copy of one record after its write was acknowledged — unless a newer local change to it is still queued. */
    private suspend fun applyEcho(entity: String, id: String, apply: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { if (id !in pendingIds(entity)) apply() } }
    }

    companion object {
        const val TRANSACTION = OutboxEntities.TRANSACTION
        const val CATEGORY = OutboxEntities.CATEGORY
        const val RECURRING = OutboxEntities.RECURRING
        const val SUBSCRIPTION = OutboxEntities.SUBSCRIPTION
        const val RECURRING_PAY = OutboxEntities.RECURRING_PAY
        private const val REFRESH_ATTEMPTS = 3
        /** Days added to the 12-month cut-off: the server cuts by its UTC date, the device counts local days. */
        private const val WINDOW_MARGIN_DAYS = 2
        private const val TRANSFER_SLUG = "transfer"
        private const val DEFAULT_SUBSCRIPTION_COLOR = "#6366F1"
    }
}
