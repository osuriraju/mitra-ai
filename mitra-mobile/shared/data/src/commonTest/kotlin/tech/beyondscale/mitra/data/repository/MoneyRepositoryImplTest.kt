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
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.repository.MoneyRepository
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
}
