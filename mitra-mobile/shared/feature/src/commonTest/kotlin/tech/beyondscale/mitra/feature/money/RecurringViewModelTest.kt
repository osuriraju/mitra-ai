package tech.beyondscale.mitra.feature.money

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.repository.PayResult
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringViewModelTest : ViewModelTest() {
    // 06:00Z on 30 Oct = 11:30 in India
    private val clock = FixedClock(Instant.parse("2026-10-30T06:00:00Z"), TimeZone.of("Asia/Kolkata"))

    private fun bill(id: String, day: Int, paise: Long = 1_500_000, lastPaidOn: String? = null, name: String = "Rent") =
        Recurring(id, name, "🏠", paise, day, "UPI", RecurringKind.Bill, true, null, "cash", null, lastPaidOn)

    private class Harness(val vm: RecurringViewModel, val events: List<UiEvent<RecurringAction>>) {
        val snacks get() = events.filterIsInstance<UiEvent.Snack<RecurringAction>>().map { it.text }
    }

    private fun TestScope.start(repo: FakeMoneyRepository): Harness {
        val vm = RecurringViewModel(repo, clock)
        val events = mutableListOf<UiEvent<RecurringAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun dueSoonWrapsMonthEnd() = runTest {
        val h = start(FakeMoneyRepository(recurring = listOf(bill("a", 2), bill("b", 30)), today = "2026-10-30"))
        val soon = h.vm.state.value.dueSoon
        assertEquals(listOf("b", "a"), soon.map { it.id })
        assertEquals("Today · UPI", soon[0].sub)
        assertTrue(soon[1].sub.startsWith("in 3 days"))
    }

    @Test fun markPaidShowsPaidSnack() = runTest {
        val repo = FakeMoneyRepository(recurring = listOf(bill("rent", 30)), today = "2026-10-30")
        repo.payResult = PayResult.Paid(
            Transaction("t1", TxKind.Expense, 1_500_000, null, "cash", null, "Rent", null, "2026-10-30", "09:00"),
        )
        val h = start(repo)
        h.vm.onAction(RecurringAction.MarkPaid("rent"))
        runCurrent()
        assertTrue("Paid · Rent · ₹15,000" in h.snacks)
    }

    @Test fun markPaidTwiceCallsRepoOnce() = runTest {
        val repo = FakeMoneyRepository(recurring = listOf(bill("rent", 30)), today = "2026-10-30")
        repo.payResult = PayResult.Paid(
            Transaction("t1", TxKind.Expense, 1_500_000, null, "cash", null, "Rent", null, "2026-10-30", "09:00"),
        )
        val h = start(repo)
        h.vm.onAction(RecurringAction.MarkPaid("rent"))
        h.vm.onAction(RecurringAction.MarkPaid("rent"))
        runCurrent()
        assertEquals(1, repo.calls.count { it == "pay:rent" })
    }

    @Test fun noAccountMessage() = runTest {
        val repo = FakeMoneyRepository(recurring = listOf(bill("rent", 30)), today = "2026-10-30")
        repo.payResult = PayResult.NoAccount
        val h = start(repo)
        h.vm.onAction(RecurringAction.MarkPaid("rent"))
        runCurrent()
        assertTrue("Add an account first" in h.snacks)
    }

    @Test fun next7DaysExcludesPaid() = runTest {
        // day 3 is three days after 30 Oct (wraps into November)
        val repo = FakeMoneyRepository(
            recurring = listOf(bill("a", 3, 100_000), bill("b", 3, 250_000, lastPaidOn = "2026-10-29", name = "Wifi")),
            today = "2026-10-30",
        )
        val s = start(repo).vm.state.value
        assertEquals("₹1,000", s.next7)
        assertEquals("1 due", s.next7Sub)
    }
}
