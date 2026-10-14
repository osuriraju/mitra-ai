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
