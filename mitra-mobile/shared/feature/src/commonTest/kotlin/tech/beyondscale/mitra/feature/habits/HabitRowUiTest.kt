package tech.beyondscale.mitra.feature.habits

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wed 14 Oct 2026 — the habit tests' local "today". */
internal const val TODAY = "2026-10-14"

internal fun testHabit(
    id: String,
    name: String = id.replaceFirstChar { it.uppercase() },
    type: HabitType = HabitType.Binary,
    target: Int = 1,
    unit: String = "",
    timeOfDay: TimeOfDay = TimeOfDay.Anytime,
    days: List<Boolean> = ALL_DAYS,
    logs: Map<String, Int> = emptyMap(),
    archived: Boolean = false,
    goalId: String? = null,
    reminder: String? = null,
    icon: String = "repeat",
) = Habit(
    id = id, name = name, type = type, target = target, unit = unit, icon = icon, timeOfDay = timeOfDay, days = days,
    reminder = reminder, goalId = goalId, gentle = true, archived = archived, createdAt = "", logs = logs,
)

/** Logs [value] on each of the [days] days ending [daysAgoEnd] days before [TODAY]. */
internal fun logRun(days: Int, daysAgoEnd: Int = 0, value: Int = 1): Map<String, Int> =
    (daysAgoEnd until daysAgoEnd + days).associate { addDays(TODAY, -it) to value }

class HabitRowUiTest {
    @Test fun measurableShowsPartialAndLogLabel() {
        val water = testHabit("water", "Water", type = HabitType.Count, target = 8, unit = "glasses", logs = mapOf(TODAY to 5))
        val row = habitRowUi(water, TODAY)
        assertEquals("5/8", row.partial)
        assertEquals("Log +1 glasses", row.checkLabel)
        assertFalse(row.done)
        assertTrue(row.sub.startsWith("5/8 glasses · 8 glasses · daily"))

        val read = testHabit("read", logs = mapOf(TODAY to 1))
        val done = habitRowUi(read, TODAY)
        assertTrue(done.done)
        assertEquals("Undo", done.checkLabel)
        assertNull(done.partial)
    }

    @Test fun openBinaryAndEmptyMeasurable() {
        val read = habitRowUi(testHabit("read"), TODAY)
        assertEquals("Mark done", read.checkLabel)
        assertTrue(read.recovering)
        assertTrue(read.sub.endsWith("Recovering — start again today"))

        val stretch = habitRowUi(testHabit("stretch", type = HabitType.Duration, target = 20, unit = "minutes"), TODAY)
        assertNull(stretch.partial) // nothing logged yet: the button shows ＋
        assertEquals("Log +10 minutes", stretch.checkLabel)
    }

    @Test fun unknownIconFallsBackToRepeat() {
        assertEquals(MitraIcons.Repeat, habitIcon("no-such-icon"))
        assertEquals(MitraIcons.Book, habitIcon("book"))
        assertEquals(MitraIcons.Circle, habitIcon("circle"))
    }
}
