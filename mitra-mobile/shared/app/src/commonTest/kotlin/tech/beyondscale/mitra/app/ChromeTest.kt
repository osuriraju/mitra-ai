package tech.beyondscale.mitra.app

import kotlin.reflect.KClass
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.feature.quickadd.QuickType
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

    @Test fun habitsGoalsRoutes() {
        assertEquals(Chrome.TabOwnBar, chromeFor(HabitsDest::class))
        assertEquals(Chrome.TabOwnBar, chromeFor(GoalsDest::class))
        listOf(HabitDetailDest::class, HabitHistoryDest::class, GoalDetailDest::class, GoalReviewDest::class)
            .forEach { assertEquals(Chrome.Detail, chromeFor(it), "$it") }
        assertEquals(Chrome.TabOwnBar, chromeForDestination { it == HabitsDest::class })
        assertEquals(Chrome.Detail, chromeForDestination { it == GoalReviewDest::class })
    }

    @Test fun wellnessNotesAiRoutes() {
        listOf(WellnessDest::class, NotesDest::class, AiDest::class).forEach { assertEquals(Chrome.TabOwnBar, chromeFor(it), "$it") }
        listOf(
            WellnessTrendsDest::class, SleepDetailDest::class, WellnessSettingsDest::class,
            NotesSearchDest::class, JournalDest::class, NoteEditorDest::class, AiSuggestionsDest::class, AiLogDest::class,
        ).forEach { assertEquals(Chrome.Detail, chromeFor(it), "$it") }
        assertEquals(Chrome.Detail, chromeForDestination { it == NoteEditorDest::class })
    }

    @Test fun quickAddTypesFollowModules() {
        assertEquals(QuickType.entries, quickTypesFor(Modules()))
        assertEquals(
            listOf(QuickType.Task, QuickType.Habit, QuickType.Note),
            quickTypesFor(Modules(money = false, wellness = false)),
        )
    }

    /** Manage categories from the transaction sheet: stay in the Money stack, else enter it via Money's root. */
    @Test fun manageCategoriesRoutesThroughTheMoneyStack() {
        assertEquals(CategoriesNav.InMoneyStack, categoriesNav(moneyOn = true, inMoneyStack = true))
        assertEquals(CategoriesNav.ViaMoneyRoot, categoriesNav(moneyOn = true, inMoneyStack = false))
        assertEquals(CategoriesNav.Unavailable, categoriesNav(moneyOn = false, inMoneyStack = false))
        assertEquals(CategoriesNav.Unavailable, categoriesNav(moneyOn = false, inMoneyStack = true))
    }

    @Test fun shellViewModelsAreKeyedPerAccount() {
        assertEquals("quickAdd:u1", shellVmKey("quickAdd", "u1"))
        kotlin.test.assertNotEquals(shellVmKey("taskEditor", "a"), shellVmKey("taskEditor", "b"))
    }
}
