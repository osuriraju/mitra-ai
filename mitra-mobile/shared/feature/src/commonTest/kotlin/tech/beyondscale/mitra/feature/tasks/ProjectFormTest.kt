package tech.beyondscale.mitra.feature.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectFormTest {
    @Test fun blankNameIsRejected() {
        assertEquals("Name the project", validateProjectName("  "))
        assertEquals("Name the project", validateProjectName(""))
    }

    @Test fun nameIsAccepted() {
        assertNull(validateProjectName("Work"))
    }

    @Test fun emojisMatchWeb() {
        assertEquals(14, PROJECT_EMOJIS.size)
        assertEquals("🚀", PROJECT_EMOJIS.first())
    }
}
