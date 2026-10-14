package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.AccountKind
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.TxSource
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PayResult
import io.ktor.http.HttpStatusCode
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MoneyRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: MoneyRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(MoneyRepositoryImpl(api, db, prefs, writer, engine, clock, backgroundScope, io), engine, prefs)
    }

    /* ---------- fixtures ---------- */

    private val cash = "a0000000-0000-4000-8000-000000000001"
    private val bank = "a0000000-0000-4000-8000-000000000002"
    private val defaultCategories = listOf(
        "food" to "🍔", "transport" to "🚕", "shopping" to "🛍️", "bills" to "🧾", "home" to "🏠", "health" to "💊",
        "entertainment" to "🎬", "education" to "📚", "travel" to "✈️", "transfer" to "🔁", "salary" to "💼", "other" to "💸",
    )

    private fun accountJson(id: String = cash, name: String = "Cash", balance: String = "0") =
        """{"id":"$id","name":"$name","mask":"","kind":"cash","balance":$balance,"archived":false}"""
    private fun categoryJson(slug: String, emoji: String) =
        """{"id":"c-$slug","name":"${slug.replaceFirstChar { it.uppercase() }}","emoji":"$emoji","kind":"${if (slug == "salary") "income" else "expense"}","slug":"$slug","archived":false}"""
    private fun txJson(id: String, amount: String, date: String = "2026-10-10", kind: String = "expense", categoryId: String? = "c-food") =
        """{"id":"$id","kind":"$kind","amount":$amount,${categoryId?.let { "\"categoryId\":\"$it\"," } ?: ""}"accountId":"$cash","merchant":"Shop","date":"$date","time":"10:00","source":"user","tags":[]}"""
    private fun bootstrap(
        transactions: List<String>,
        accounts: List<String> = listOf(accountJson()),
        categories: List<String> = defaultCategories.map { categoryJson(it.first, it.second) },
    ) = """{"accounts":[${accounts.joinToString(",")}],"categories":[${categories.joinToString(",")}],"transactions":[${transactions.joinToString(",")}],"recurring":[],"subscriptions":[]}"""

    private fun seedAccount(balancePaise: Long, id: String = cash) =
        db.accountsQueries.upsert(Account(id, "Cash", "", AccountKind.Cash, balancePaise).toRow(0))
    private fun seedTx(id: String, paise: Long, date: String = "2026-10-10") =
        db.transactionsQueries.upsert(Transaction(id, TxKind.Expense, paise, null, cash, null, "Shop", null, date, "10:00").toRow())
    private fun localTx(id: String) = db.transactionsQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    private fun txIds() = db.transactionsQueries.selectIds().executeAsList().sorted()
    private fun balance(id: String = cash) = db.accountsQueries.selectById(id).executeAsOne().balancePaise
    private fun queue() = db.outboxQueries.selectAll().executeAsList()
    private fun localRecurring() = db.recurringQueries.selectAll().executeAsList().map { it.toDomain() }
    private fun localSubscriptions() = db.subscriptionsQueries.selectAll().executeAsList().map { it.toDomain() }
    private fun expense(paise: Long, kind: TxKind = TxKind.Expense) =
        TxDraft(kind, paise, null, cash, null, "Chai", null, "2026-10-14", "09:30")

    /* ---------- refresh ---------- */

    @Test fun refreshReplacesLocalData() = runTest {
        val e = env()
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(txJson("t1", "450"), txJson("t2", "99.5")), listOf(accountJson(balance = "1500.5")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf(Account(cash, "Cash", "", AccountKind.Cash, 150050)), e.repo.accounts.first())
        val cats = e.repo.categories.first()
        assertEquals(12, cats.size)
        assertEquals("food", cats.first().slug) // server order kept
        assertEquals(listOf(45000L, 9950L), e.repo.transactions.first().sortedBy { it.id }.map { it.amountPaise })

        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(txJson("t1", "450")), listOf(accountJson(balance = "1500.5")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf("t1"), txIds())
    }

    @Test fun refreshKeepsRowsWithPendingChanges() = runTest {
        val e = env()
        seedAccount(0)
        seedTx("t1", 45000)
        server.offline = true
        e.repo.updateTransaction("t1", expense(50000))
        val t2 = e.repo.addTransaction(expense(1000))
        server.offline = false
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(txJson("t1", "450")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(50000L, localTx("t1")!!.amountPaise)
        assertNotNull(localTx(t2.id))
    }

    @Test fun refreshKeepsOldTransactionsOutsideWindow() = runTest {
        val e = env()
        seedTx("old", 1000, date = "2025-09-01")
        seedTx("recent", 1000, date = "2026-10-01")
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(emptyList())) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf("old"), txIds())
    }

    @Test fun refreshTakesAnOldTransactionMovedIntoTheWindow() = runTest {
        val e = env()
        seedTx("x", 1000, date = "2025-09-01")
        // Edited on the web: its date now falls inside the 12 months the server sends.
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(txJson("x", "25", date = "2026-10-02")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals("2026-10-02" to 2500L, localTx("x")!!.let { it.date to it.amountPaise })
    }

    @Test fun localWritesTrimMerchantAndNote() = runTest {
        val e = env()
        seedAccount(0)
        server.offline = true
        val tx = e.repo.addTransaction(expense(1000).copy(merchant = "  Chai Point ", note = "   "))
        assertEquals("Chai Point" to null, localTx(tx.id)!!.let { it.merchant to it.note })
        e.repo.updateTransaction(tx.id, expense(1000).copy(merchant = " Cafe ", note = " team lunch "))
        assertEquals("Cafe" to "team lunch", localTx(tx.id)!!.let { it.merchant to it.note })
        assertTrue("\"merchant\":\"Cafe\",\"note\":\"team lunch\"" in queue().last().body!!)
    }

    @Test fun refreshKeepsLocalBalanceWhilePending() = runTest {
        val e = env()
        seedAccount(120000)
        server.offline = true
        val tx = e.repo.addTransaction(expense(20000))
        assertEquals(100000L, balance())
        server.offline = false
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(emptyList(), listOf(accountJson(name = "Wallet", balance = "1200")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(100000L, balance())
        assertEquals("Wallet", e.repo.accounts.first().single().name) // other fields still follow the server

        server.routes["POST /api/money/transactions"] = { json(txJson(tx.id, "200", date = "2026-10-14", categoryId = null)) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(txJson(tx.id, "200", date = "2026-10-14", categoryId = null)), listOf(accountJson(balance = "1000")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(100000L, balance())
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        seedAccount(0)
        var gets = 0
        var added: Transaction? = null
        server.routes["GET /api/money/bootstrap"] = {
            gets++
            if (gets == 1) added = e.repo.addTransaction(expense(1000)) // written while the snapshot is on the wire
            json(bootstrap(emptyList())) // stale: knows nothing of the new row
        }
        val result = e.repo.refresh()
        assertTrue(result is Outcome.Ok || (result as Outcome.Err).error.let { it is AppError.Server && it.code == MoneyRepository.REFRESH_SUPERSEDED })
        assertNotNull(localTx(added!!.id))
        assertEquals(-1000L, balance())
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/money/bootstrap"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID)
            json(bootstrap(listOf(txJson("t1", "450"))))
        }
        e.repo.refresh()
        assertEquals(emptyList(), txIds())
        assertEquals(emptyList(), db.accountsQueries.selectIds().executeAsList())
        assertEquals(emptyList(), db.categoriesQueries.selectIds().executeAsList())
    }

    /* ---------- transaction writes ---------- */

    @Test fun offlineCreateEditDeleteReachesServerInOrder() = runTest {
        val e = env()
        seedAccount(0)
        server.offline = true
        val tx = e.repo.addTransaction(expense(45000))
        e.repo.updateTransaction(tx.id, expense(50000))
        assertEquals(tx.copy(amountPaise = 50000), e.repo.deleteTransaction(tx.id))
        val rows = queue()
        assertEquals(
            listOf("POST money/transactions", "PATCH money/transactions/${tx.id}", "DELETE money/transactions/${tx.id}"),
            rows.map { "${it.method} ${it.path}" },
        )
        assertTrue(rows.all { it.entity == OutboxEntities.TRANSACTION && it.entityId == tx.id })
        assertEquals(
            """{"id":"${tx.id}","kind":"expense","amount":450.0,"categoryId":null,"accountId":"$cash","toAccountId":null,"merchant":"Chai","note":null,"date":"2026-10-14","time":"09:30","source":"user","recurringId":null,"tags":[]}""",
            rows[0].body,
        )
        assertEquals(
            """{"kind":"expense","amount":500.0,"categoryId":null,"accountId":"$cash","toAccountId":null,"merchant":"Chai","note":null,"date":"2026-10-14","time":"09:30"}""",
            rows[1].body,
        )
        assertNull(rows[2].body)

        server.offline = false
        val created = mutableSetOf<String>()
        server.routes["POST /api/money/transactions"] = { created += tx.id; json(txJson(tx.id, "450", date = "2026-10-14", categoryId = null)) }
        server.routes["PATCH /api/money/transactions/${tx.id}"] = { req ->
            assertTrue(tx.id in created)
            assertTrue("\"amount\":500.0" in req.body.toByteArray().decodeToString())
            json(txJson(tx.id, "500", date = "2026-10-14", categoryId = null))
        }
        server.routes["DELETE /api/money/transactions/${tx.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(
            listOf("POST /api/money/transactions", "PATCH /api/money/transactions/${tx.id}", "DELETE /api/money/transactions/${tx.id}"),
            server.log,
        )
        assertEquals(emptyList(), e.repo.transactions.first())
        assertEquals(0L, balance())
    }

    @Test fun balanceEffectsReverseExactly() = runTest {
        val e = env()
        seedAccount(0)
        server.offline = true
        val tx = e.repo.addTransaction(expense(45000))
        assertEquals(-45000L, balance())
        e.repo.updateTransaction(tx.id, expense(10000, TxKind.Income))
        assertEquals(10000L, balance())
        e.repo.deleteTransaction(tx.id)
        assertEquals(0L, balance())

        // The POST's answer lands after the row was deleted locally: no resurrection, no balance change.
        server.offline = false
        server.routes["POST /api/money/transactions"] = { json(txJson(tx.id, "450", date = "2026-10-14", categoryId = null)) }
        server.routes["PATCH /api/money/transactions/${tx.id}"] = { json(txJson(tx.id, "100", date = "2026-10-14", kind = "income", categoryId = null)) }
        server.routes["DELETE /api/money/transactions/${tx.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertNull(localTx(tx.id))
        assertEquals(0L, balance())
    }

    @Test fun echoDoesNotResurrectAfterDeleteWithoutQueue() = runTest {
        val e = env()
        seedAccount(0)
        server.offline = true
        val tx = e.repo.addTransaction(expense(45000))
        server.offline = false
        // While the POST is on the wire the user deletes it; the queued DELETE follows.
        server.routes["POST /api/money/transactions"] = {
            e.repo.deleteTransaction(tx.id)
            json(txJson(tx.id, "450", date = "2026-10-14", categoryId = null))
        }
        server.routes["DELETE /api/money/transactions/${tx.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertNull(localTx(tx.id))
        assertEquals(0L, balance())
    }

    @Test fun restoreRecreatesWithSameIdAndBalance() = runTest {
        val e = env()
        seedAccount(0)
        server.offline = true
        val tx = e.repo.addTransaction(expense(45000))
        val removed = assertNotNull(e.repo.deleteTransaction(tx.id))
        e.repo.restoreTransaction(removed)
        assertEquals(tx, localTx(tx.id))
        assertEquals(-45000L, balance())
        assertEquals(listOf("POST", "DELETE", "POST"), queue().map { it.method })
        assertNull(e.repo.deleteTransaction("missing"))
    }

    /* ---------- missing accounts ---------- */

    @Test fun refreshKeepsAnAccountQueuedTransactionsStillUse() = runTest {
        val e = env()
        seedAccount(0)
        seedAccount(5000, id = bank)
        server.offline = true
        val tx = e.repo.addTransaction(expense(1000).copy(accountId = bank))
        server.offline = false
        // The bank account was archived on the web: the server no longer lists it.
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(emptyList())) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf(cash, bank), e.repo.accounts.first().map { it.id })
        assertEquals(4000L, balance(bank))

        val onBank = txJson(tx.id, "10", date = "2026-10-14", categoryId = null).replace(cash, bank)
        server.routes["POST /api/money/transactions"] = { json(onBank) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(onBank))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        // Nothing queued any more: the account goes, its transactions stay readable without it.
        assertEquals(listOf(cash), db.accountsQueries.selectIds().executeAsList())
        assertEquals(bank, e.repo.transactions.first().single { it.id == tx.id }.accountId)
    }

    /* ---------- categories ---------- */

    private fun seedCategory(id: String, slug: String?, position: Long = 0) =
        db.categoriesQueries.upsert(Category(id, id, "🏷️", CategoryKind.Expense, slug).toRow(position))

    @Test fun addAndEditCategoryQueueBodies() = runTest {
        val e = env()
        seedCategory("c-food", "food")
        server.offline = true
        val c = e.repo.addCategory(" Pets ", "🐶", CategoryKind.Expense, 150000)
        assertEquals(1L, db.categoriesQueries.selectById(c.id).executeAsOne().position)
        e.repo.updateCategory(c.id, listOf(CategoryChange.Budget(null)))
        e.repo.updateCategory(c.id, listOf(CategoryChange.Name("Pet care")))
        assertEquals(c.copy(name = "Pet care", budgetPaise = null), e.repo.categories.first().last())
        val rows = queue()
        assertEquals(listOf("POST money/categories", "PATCH money/categories/${c.id}"), rows.map { "${it.method} ${it.path}" })
        assertEquals("""{"id":"${c.id}","name":"Pets","emoji":"🐶","kind":"expense","budget":1500.0}""", rows[0].body)
        assertEquals("""{"budget":null,"name":"Pet care"}""", rows[1].body)
    }

    @Test fun deleteCategoryUncategorisesTransactions() = runTest {
        val e = env()
        seedCategory("c-food", "food")
        db.transactionsQueries.upsert(Transaction("t1", TxKind.Expense, 1000, "c-food", cash, null, "Shop", null, "2026-10-10", "10:00").toRow())
        server.offline = true
        e.repo.deleteCategory("c-food")
        assertNull(localTx("t1")!!.categoryId)
        assertEquals(emptyList(), e.repo.categories.first())
        val row = queue().single()
        assertEquals("DELETE money/categories/c-food" to "c-food", "${row.method} ${row.path}" to row.entityId)

        // A refresh before the DELETE lands still shows the transaction uncategorised.
        server.offline = false
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(listOf(txJson("t1", "10")), categories = listOf(categoryJson("food", "🍔")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertNull(localTx("t1")!!.categoryId)
        assertNull(db.categoriesQueries.selectById("c-food").executeAsOneOrNull())
    }

    @Test fun deletingTheTransferCategoryKeepsItsTransactionsLinked() = runTest {
        val e = env()
        seedCategory("c-transfer", "transfer")
        db.transactionsQueries.upsert(Transaction("t1", TxKind.Transfer, 1000, "c-transfer", cash, bank, "", null, "2026-10-10", "10:00").toRow())
        server.offline = true
        e.repo.deleteCategory("c-transfer")
        assertEquals("c-transfer", localTx("t1")!!.categoryId) // the server archives it; its transfers keep pointing at it
        assertEquals(emptyList(), e.repo.categories.first())
    }

    /* ---------- recurring ---------- */

    private val rentId = "r0000000-0000-4000-8000-000000000001"
    private fun seedRent(accountId: String? = null, categoryId: String? = null, lastPaidOn: String? = null, kind: RecurringKind = RecurringKind.Bill, to: String? = null) =
        db.recurringQueries.upsert(Recurring(rentId, "Rent", "🏠", 1500000, 5, "UPI", kind, true, categoryId, accountId, to, lastPaidOn).toRow())
    private fun localRent() = db.recurringQueries.selectById(rentId).executeAsOne().toDomain()

    @Test fun addRecurringClampsDayAndQueuesBody() = runTest {
        val e = env()
        server.offline = true
        val r = e.repo.addRecurring(RecurringDraft("Gym", "🏋️", 150000, 31, RecurringKind.Bill, reminder = false))
        assertEquals(28, r.day)
        assertEquals("UPI", r.method)
        assertEquals(r, e.repo.recurring.first().single())
        assertEquals(
            """{"id":"${r.id}","name":"Gym","emoji":"🏋️","amount":1500.0,"day":28,"method":"UPI","kind":"bill","reminder":false,"categoryId":null,"accountId":null,"toAccountId":null}""",
            queue().single().body,
        )
        e.repo.setRecurringReminder(r.id, true)
        assertEquals(true, localRecurring().single().reminder) // the shared flow may still replay its last value
        assertEquals("""{"reminder":true}""", queue().last().body)
        assertEquals("recurring:${r.id}", queue().last().coalesceKey)
    }

    @Test fun deleteRecurringUnlinksPastTransactions() = runTest {
        val e = env()
        seedRent()
        db.transactionsQueries.upsert(Transaction("t1", TxKind.Expense, 1500000, null, cash, null, "Rent", null, "2026-09-05", "10:00", TxSource.Recurring, rentId).toRow())
        server.offline = true
        e.repo.deleteRecurring(rentId)
        assertEquals(emptyList(), e.repo.recurring.first())
        assertNull(localTx("t1")!!.recurringId)
        assertEquals("DELETE money/recurring/$rentId", queue().single().let { "${it.method} ${it.path}" })
    }

    @Test fun paySendsClientIdAndLocalDate() = runTest {
        clock.instant = Instant.parse("2026-11-01T00:30:00+05:30") // UTC is still 31 Oct
        val e = env()
        seedAccount(0)
        seedRent()
        server.offline = true
        val paid = assertIs<PayResult.Paid>(e.repo.payRecurring(rentId))
        val tx = paid.transaction
        assertEquals(tx, localTx(tx.id))
        assertEquals("2026-11-01", tx.date)
        assertEquals("00:30", tx.time)
        assertEquals(TxSource.Recurring, tx.source)
        assertEquals(rentId, tx.recurringId)
        assertEquals("2026-11-01", localRent().lastPaidOn)
        assertEquals(-1500000L, balance())
        val row = queue().single()
        assertEquals(Triple(OutboxEntities.RECURRING_PAY, "POST money/recurring/$rentId/pay", rentId), Triple(row.entity, "${row.method} ${row.path}", row.entityId))
        assertNull(row.coalesceKey)
        assertEquals("""{"id":"${tx.id}","accountId":"$cash","date":"2026-11-01"}""", row.body)

        // The server's answer (its own clock says 31 Oct, 19:00) doesn't move the local day, time or balance.
        server.offline = false
        server.routes["POST /api/money/recurring/$rentId/pay"] = {
            json("""{"transaction":${txJson(tx.id, "15000", date = "2026-11-01", categoryId = null).replace("\"time\":\"10:00\"", "\"time\":\"19:00\"").replace("Shop", "Rent").replace("\"source\":\"user\"", "\"source\":\"recurring\",\"recurringId\":\"$rentId\"")},"recurring":{"id":"$rentId","name":"Rent","emoji":"🏠","amount":15000,"day":5,"method":"UPI","kind":"bill","reminder":true,"lastPaidOn":"2026-11-01"}}""")
        }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(tx, localTx(tx.id))
        assertEquals("2026-11-01", localRent().lastPaidOn)
        assertEquals(-1500000L, balance())
    }

    @Test fun payTwiceQueuesOnce() = runTest {
        val e = env()
        seedAccount(0)
        seedRent()
        server.offline = true
        assertIs<PayResult.Paid>(e.repo.payRecurring(rentId))
        assertEquals(PayResult.AlreadyPaid, e.repo.payRecurring(rentId))
        assertEquals(1, txIds().size)
        assertEquals(1, queue().count { it.entity == OutboxEntities.RECURRING_PAY })
        assertEquals(-1500000L, balance())
    }

    @Test fun payQueuedIsAlreadyPaidEvenIfLastPaidOnWasOverwritten() = runTest {
        val e = env()
        seedAccount(0)
        seedRent()
        server.offline = true
        assertIs<PayResult.Paid>(e.repo.payRecurring(rentId))
        seedRent(lastPaidOn = "2026-09-05") // e.g. an older copy written back
        assertEquals(PayResult.AlreadyPaid, e.repo.payRecurring(rentId))
    }

    @Test fun payResentAfterLostResponseLeavesOneTransaction() = runTest {
        val e = env()
        seedAccount(0)
        seedRent()
        server.offline = true
        val tx = assertIs<PayResult.Paid>(e.repo.payRecurring(rentId)).transaction
        server.offline = false
        var posts = 0
        server.routes["POST /api/money/recurring/$rentId/pay"] = {
            posts++
            // The first attempt is recorded by the server but its answer is lost; resends hit the existing id.
            if (posts == 1) throw Exception("connection reset")
            json(errorJson("INTERNAL", "Something went wrong"), HttpStatusCode.InternalServerError)
        }
        server.routes["GET /api/money/bootstrap"] = {
            json(bootstrap(listOf(txJson(tx.id, "15000", date = "2026-10-14", categoryId = null)), listOf(accountJson(balance = "-15000"))).replace(
                "\"recurring\":[]",
                """"recurring":[{"id":"$rentId","name":"Rent","emoji":"🏠","amount":15000,"day":5,"method":"UPI","kind":"bill","reminder":true,"lastPaidOn":"2026-10-14"}]""",
            ))
        }
        repeat(4) { e.engine.drain(scheduleRetryOnFailure = false) }
        assertEquals(emptyList(), queue())
        assertEquals(4, posts)
        assertEquals(listOf(tx.id), txIds())
        assertEquals("2026-10-14", localRent().lastPaidOn)
        assertEquals(-1500000L, balance())
    }

    @Test fun payWithoutAccountReturnsNoAccount() = runTest {
        val e = env()
        seedRent()
        assertEquals(PayResult.NoAccount, e.repo.payRecurring(rentId))
        assertEquals(PayResult.NotFound, e.repo.payRecurring("missing"))
        assertEquals(emptyList(), queue())
        assertEquals(emptyList(), txIds())
        assertNull(localRent().lastPaidOn)
    }

    @Test fun payTransferWithoutDestinationReturnsNoDestination() = runTest {
        val e = env()
        seedAccount(0)
        seedRent(kind = RecurringKind.Transfer)
        assertEquals(PayResult.NoDestination, e.repo.payRecurring(rentId))
        assertEquals(emptyList(), queue())
    }

    @Test fun payFallsBackToHomeCategoryForBills() = runTest {
        val e = env()
        seedAccount(0)
        seedAccount(0, id = bank)
        seedCategory("c-food", "food")
        seedCategory("c-home", "home", 1)
        seedRent(accountId = bank)
        server.offline = true
        val tx = assertIs<PayResult.Paid>(e.repo.payRecurring(rentId)).transaction
        assertEquals("c-home", tx.categoryId)
        assertEquals(bank, tx.accountId)
        assertEquals(TxKind.Expense, tx.kind)
    }

    @Test fun refreshKeepsRecurringWithQueuedPayment() = runTest {
        val e = env()
        seedAccount(0)
        seedRent()
        server.offline = true
        e.repo.payRecurring(rentId)
        server.offline = false
        server.routes["GET /api/money/bootstrap"] = {
            json(bootstrap(emptyList()).replace(
                "\"recurring\":[]",
                """"recurring":[{"id":"$rentId","name":"Rent","emoji":"🏠","amount":15000,"day":5,"method":"UPI","kind":"bill","reminder":true}]""",
            ))
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals("2026-10-14", localRent().lastPaidOn)
        assertEquals(-1500000L, balance())
    }

    /* ---------- subscriptions ---------- */

    @Test fun addSubscriptionDefaultsNextAndLetter() = runTest {
        val e = env()
        server.offline = true
        val s = e.repo.addSubscription("netflix", 64900, Cycle.Monthly)
        assertEquals("2026-11-13", s.next)
        assertEquals("N", s.letter)
        assertEquals("2027-10-14", e.repo.addSubscription("Prime", 149900, Cycle.Yearly).next)
        assertEquals(s, e.repo.subscriptions.first().first { it.id == s.id })
        val body = queue().first().body!!
        assertTrue("\"color\":\"#6366F1\"" in body, body)
        assertEquals(
            """{"id":"${s.id}","name":"netflix","amount":649.0,"cycle":"Monthly","next":"2026-11-13","color":"#6366F1","letter":"N","lastUsed":null,"categoryId":null,"accountId":null}""",
            body,
        )
    }

    @Test fun markUsedAndDeleteSubscription() = runTest {
        val e = env()
        server.offline = true
        val s = e.repo.addSubscription("Spotify", 11900, Cycle.Monthly)
        e.repo.markSubscriptionUsed(s.id)
        e.repo.markSubscriptionUsed(s.id)
        assertEquals("2026-10-14", localSubscriptions().single().lastUsed)
        e.repo.deleteSubscription(s.id)
        assertEquals(emptyList(), localSubscriptions())
        val rows = queue()
        assertEquals(listOf("POST", "PATCH", "DELETE"), rows.map { it.method })
        assertEquals("""{"lastUsed":"2026-10-14"}""", rows[1].body)
        assertEquals("subscription:${s.id}", rows[1].coalesceKey)
    }

    @Test fun echoesOfOtherEntitiesFollowTheServerUnlessDeleted() = runTest {
        val e = env()
        server.offline = true
        val c = e.repo.addCategory("Pets", "🐶", CategoryKind.Expense)
        val s = e.repo.addSubscription("Spotify", 11900, Cycle.Monthly)
        e.repo.deleteSubscription(s.id)
        server.offline = false
        server.routes["POST /api/money/categories"] = { json("""{"id":"${c.id}","name":"Pets","emoji":"🐾","kind":"expense","archived":false}""") }
        server.routes["POST /api/money/subscriptions"] = { json("""{"id":"${s.id}","name":"Spotify","amount":119,"cycle":"Monthly","next":"2026-11-13","color":"#6366F1","letter":"S"}""") }
        server.routes["DELETE /api/money/subscriptions/${s.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals("🐾", e.repo.categories.first().single().emoji)
        assertEquals(emptyList(), e.repo.subscriptions.first())
    }

    @Test fun rejectedCategoryWriteRefreshes() = runTest {
        val e = env()
        server.offline = true
        e.repo.addCategory("Pets", "🐶", CategoryKind.Expense)
        server.offline = false
        server.routes["POST /api/money/categories"] = { json(errorJson("VALIDATION", "Bad"), HttpStatusCode.BadRequest) }
        server.routes["GET /api/money/bootstrap"] = { json(bootstrap(emptyList(), categories = listOf(categoryJson("food", "🍔")))) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("c-food"), e.repo.categories.first().map { it.id })
    }
}
