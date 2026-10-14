package tech.beyondscale.mitra.domain.money

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyWidgetInputsTest {
    private val a = testUser(id = "a")
    private val b = testUser(id = "b")

    @Test fun sessionEndedReadsAsSignedOut() {
        assertFalse(widgetSignedIn(a, SessionState.SignedOut)) // 401: Login shown, cached user kept
        assertTrue(widgetSignedIn(a, SessionState.SignedIn(a)))
        assertTrue(widgetSignedIn(a, SessionState.Unknown)) // widget-only process: session not restored yet
        assertFalse(widgetSignedIn(null, SessionState.Unknown))
    }

    @Test fun sessionEndedGivesSignedOutModel() = runTest {
        val session = MutableStateFlow<SessionState>(SessionState.SignedIn(a))
        val seen = mutableListOf<MoneyWidgetModel>()
        val job = launch {
            inputs(MutableStateFlow(listOf(food(3_000_000))), MutableStateFlow(listOf(tx("f", 100_000))), MutableStateFlow<User?>(a), session)
                .collect { seen += it.model(hideAmounts = false) }
        }
        runCurrent()
        session.value = SessionState.SignedOut
        runCurrent()
        assertTrue(seen.first() is MoneyWidgetModel.Budget)
        assertEquals(MoneyWidgetModel.SignedOut, seen.last())
        job.cancel()
    }

    @Test fun moduleFlagComesFromTheLocalUser() = runTest {
        val off = testUser(id = "a", settings = Settings(modules = Modules(money = false)))
        val seen = mutableListOf<MoneyWidgetModel>()
        val job = launch {
            inputs(MutableStateFlow(listOf(food(3_000_000))), MutableStateFlow(emptyList()), MutableStateFlow<User?>(off), MutableStateFlow(SessionState.SignedIn(off)))
                .collect { seen += it.model(hideAmounts = false) }
        }
        runCurrent()
        assertEquals(listOf<MoneyWidgetModel>(MoneyWidgetModel.MoneyOff), seen)
        job.cancel()
    }

    @Test fun accountSwitchNeverPairsTheNewUserWithTheOldLists() = runTest {
        val cats = MutableStateFlow(listOf(food(3_000_000)))
        val txs = MutableStateFlow(listOf(tx("f", 1_840_000)))
        val user = MutableStateFlow<User?>(a)
        val seen = mutableListOf<MoneyWidgetInputs>()
        val job = launch { inputs(cats, txs, user, MutableStateFlow(SessionState.Unknown)).collect { seen += it } }
        runCurrent()
        assertEquals(1, seen.size) // first frame is immediate

        user.value = b // the user row lands before the cleared lists re-query
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(ACCOUNT_SWITCH_SETTLE_MS / 2)
        cats.value = emptyList()
        txs.value = emptyList()
        advanceTimeBy(ACCOUNT_SWITCH_SETTLE_MS)
        runCurrent()
        assertEquals(2, seen.size)
        assertEquals(MoneyWidgetModel.NoBudget("₹0"), seen.last().model(hideAmounts = false))
        assertTrue(seen.none { it.userId == "b" && it.categories.isNotEmpty() })

        txs.value = listOf(tx("n", 5_000)) // after the settle window, changes pass straight through
        runCurrent()
        assertEquals(3, seen.size)
        job.cancel()
    }

    private fun inputs(
        cats: MutableStateFlow<List<Category>>,
        txs: MutableStateFlow<List<Transaction>>,
        user: MutableStateFlow<User?>,
        session: MutableStateFlow<SessionState>,
    ) = moneyWidgetInputs(cats, txs, flowOf(emptyList()), flowOf(emptyList()), user, session, flowOf("2026-10-14"))
}
