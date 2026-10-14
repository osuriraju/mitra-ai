package tech.beyondscale.mitra.app

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class ChromeTest {
    @Test fun mapsRoutesToChrome() {
        assertEquals(Chrome.Detail, chromeFor(TaskDetailDest::class))
        assertEquals(Chrome.Detail, chromeFor(ProjectDetailDest::class))
        assertEquals(Chrome.Fullscreen, chromeFor(FocusDest::class))
        assertEquals(Chrome.Tab, chromeFor(TodayDest::class))
        assertEquals(Chrome.TabOwnBar, chromeFor(TasksDest::class))
        assertEquals(Chrome.Tab, chromeFor(null))
    }

    /** What MainShell does: resolve the chrome from the current destination's `hasRoute`. */
    @Test fun resolvesChromeFromTheCurrentDestination() {
        fun at(route: KClass<*>) = chromeForDestination { it == route }
        assertEquals(Chrome.TabOwnBar, at(TasksDest::class))
        assertEquals(Chrome.Detail, at(TaskDetailDest::class))
        assertEquals(Chrome.Detail, at(ProjectDetailDest::class))
        assertEquals(Chrome.Fullscreen, at(FocusDest::class))
        assertEquals(Chrome.Tab, at(TodayDest::class))
        assertEquals(Chrome.Tab, at(SettingsDest::class))
        assertEquals(Chrome.Tab, chromeForDestination { false })
    }

    @Test fun moneyRoutes() {
        assertEquals(Chrome.TabOwnBar, chromeFor(MoneyDest::class))
        listOf(TransactionsDest::class, BudgetsDest::class, CategoriesDest::class, RecurringDest::class, SubscriptionsDest::class)
            .forEach { assertEquals(Chrome.Detail, chromeFor(it), "$it") }
        assertEquals(Chrome.TabOwnBar, chromeForDestination { it == MoneyDest::class })
        assertEquals(Chrome.Detail, chromeForDestination { it == BudgetsDest::class })
    }

    @Test fun shellViewModelsAreKeyedPerAccount() {
        assertEquals("quickAdd:u1", shellVmKey("quickAdd", "u1"))
        kotlin.test.assertNotEquals(shellVmKey("taskEditor", "a"), shellVmKey("taskEditor", "b"))
    }
}
