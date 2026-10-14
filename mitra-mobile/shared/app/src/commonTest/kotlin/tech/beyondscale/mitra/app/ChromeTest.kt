package tech.beyondscale.mitra.app

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

    @Test fun shellViewModelsAreKeyedPerAccount() {
        assertEquals("quickAdd:u1", shellVmKey("quickAdd", "u1"))
        kotlin.test.assertNotEquals(shellVmKey("taskEditor", "a"), shellVmKey("taskEditor", "b"))
    }
}
