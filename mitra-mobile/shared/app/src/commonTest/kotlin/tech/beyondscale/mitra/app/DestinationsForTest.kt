package tech.beyondscale.mitra.app

import tech.beyondscale.mitra.domain.model.Modules
import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationsForTest {
    private fun labels(m: Modules) = destinationsFor(m).let { (t, r) -> t.map { it.label } to r.map { it.label } }

    @Test fun moneyOn() {
        val (tabs, more) = labels(Modules())
        assertEquals(listOf("Today", "Tasks", "Money"), tabs)
        assertEquals(listOf("Habits", "Goals", "Wellness", "Notes", "AI", "Settings"), more)
    }

    @Test fun moneyOffMovesHabitsToTabs() {
        val (tabs, more) = labels(Modules(money = false))
        assertEquals(listOf("Today", "Tasks", "Habits"), tabs)
        assertEquals(listOf("Goals", "Wellness", "Notes", "AI", "Settings"), more)
    }

    @Test fun wellnessAndAiOffAreExcluded() {
        val (_, more) = labels(Modules(wellness = false, ai = false))
        assertEquals(listOf("Habits", "Goals", "Notes", "Settings"), more)
    }
}
