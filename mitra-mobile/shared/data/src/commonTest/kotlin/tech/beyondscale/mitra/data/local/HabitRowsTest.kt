package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Habits
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.THREE_A_WEEK
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import kotlin.test.Test
import kotlin.test.assertEquals

class HabitRowsTest {
    @Test fun roundTripsEveryField() {
        val habit = Habit(
            id = "h1", name = "Read", type = HabitType.Duration, target = 20, unit = "min", icon = "book",
            timeOfDay = TimeOfDay.Evening, days = WEEKDAYS, reminder = null, goalId = "g1", gentle = false,
            archived = true, createdAt = "2026-10-01T04:30:00Z", logs = mapOf("2026-10-13" to 1),
        )
        val row = habit.toRow(position = 3)
        assertEquals(3L, row.position)
        assertEquals("1111100", row.days)
        assertEquals(habit, row.toDomain(habit.logs))

        val withReminder = habit.copy(reminder = "07:30", goalId = null, archived = false, gentle = true)
        assertEquals(withReminder, withReminder.toRow(0).toDomain(withReminder.logs))

        assertEquals(ALL_DAYS, textToDays("bad"))
        assertEquals(ALL_DAYS, textToDays("11111"))
        assertEquals(THREE_A_WEEK, textToDays(daysToText(THREE_A_WEEK)))
    }

    @Test fun unknownWireEnumFallsBack() {
        val row = Habits("h1", "x", "weird", 1, "", "repeat", "noon", "1111111", null, null, 1, 0, "", 0)
        val habit = row.toDomain(emptyMap())
        assertEquals(HabitType.Binary, habit.type)
        assertEquals(TimeOfDay.Anytime, habit.timeOfDay) // same fallback as TimeOfDay.fromWire
    }
}
