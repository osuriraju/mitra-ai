package tech.beyondscale.mitra.feature.money

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest : ViewModelTest() {
    // 10:00 on 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    private fun sub(id: String, paise: Long, cycle: Cycle = Cycle.Monthly, lastUsed: String? = null, name: String = "Netflix") =
        Subscription(id, name, paise, cycle, "2026-10-20", "#E50914", name.take(1), lastUsed, null, null)

    private fun TestScope.state(vararg subs: Subscription): SubscriptionsUiState {
        val vm = SubscriptionsViewModel(FakeMoneyRepository(subscriptions = subs.toList()), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm.state.value
    }

    @Test fun monthlyFoldsYearly() = runTest {
        val s = state(sub("a", 64_900), sub("b", 149_900, Cycle.Yearly, name = "Prime"))
        assertEquals("₹774", s.monthly)
        assertEquals("₹9,287", s.yearly)
        assertEquals("2 active", s.monthlySub)
    }

    @Test fun unusedAfter30Days() = runTest {
        val s = state(sub("a", 64_900, lastUsed = "2026-09-13"))
        assertEquals("1", s.unusedCount)
        assertEquals("Netflix", s.unusedSub)
        assertEquals("Not used 31d", s.rows[0].unusedChip)
        val t = state(sub("a", 64_900, lastUsed = "2026-09-14"))
        assertEquals("0", t.unusedCount)
        assertEquals("none", t.unusedSub)
        assertEquals(null, t.rows[0].unusedChip)
    }
}
