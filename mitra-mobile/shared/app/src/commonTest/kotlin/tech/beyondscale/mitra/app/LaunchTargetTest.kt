package tech.beyondscale.mitra.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchTargetTest {
    @Test fun parsesKnownUris() {
        assertEquals(LaunchTarget.OpenTask("abc"), parseLaunchUri("mitra://task/abc"))
        assertEquals(LaunchTarget.OpenTasks, parseLaunchUri("mitra://tasks"))
        assertEquals(LaunchTarget.QuickAddTask, parseLaunchUri("mitra://quick-add/task"))
    }

    @Test fun moneyUris() {
        assertEquals(LaunchTarget.OpenMoney, parseLaunchUri("mitra://money"))
        assertEquals(LaunchTarget.QuickAddExpense, parseLaunchUri("mitra://quick-add/expense"))
        assertEquals(LaunchTarget.OpenBudgets, parseLaunchUri("mitra://money/budgets"))
        assertNull(parseLaunchUri("mitra://money/x"))
        assertNull(parseLaunchUri("mitra://money/"))
        assertNull(parseLaunchUri("mitra://money/budgets/1"))
        assertNull(parseLaunchUri("mitra://quick-add/expense/x"))
    }

    @Test fun wellnessAiAndShareTargets() {
        assertEquals(LaunchTarget.OpenCheckin, parseLaunchUri("mitra://checkin"))
        assertEquals(LaunchTarget.OpenAi, parseLaunchUri("mitra://ai"))
        assertNull(parseLaunchUri("mitra://ai/x"))
        assertEquals(LaunchTarget.QuickAddNote("Read later\nhttps://x.dev/a"), sharedNoteTarget("Read later", " https://x.dev/a "))
        assertEquals(LaunchTarget.QuickAddNote("https://x.dev/a"), sharedNoteTarget("x.dev", "https://x.dev/a"))
        assertEquals(LaunchTarget.QuickAddNote("Just a subject"), sharedNoteTarget("Just a subject", null))
        assertNull(sharedNoteTarget(" ", ""))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(parseLaunchUri("mitra://task/"))
        assertNull(parseLaunchUri("https://x/task/1"))
        assertNull(parseLaunchUri(null))
    }

    @Test fun submitNullKeepsThePendingTargetAndConsumeClearsIt() {
        val requests = LaunchRequests()
        requests.submit(LaunchTarget.OpenTasks)
        requests.submit(null)
        assertEquals(LaunchTarget.OpenTasks, requests.pending.value)
        requests.consume()
        assertNull(requests.pending.value)
    }
}
