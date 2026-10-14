package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import kotlin.test.Test
import kotlin.test.assertEquals

class HabitPatchesTest {
    @Test fun clearsSendNull() {
        assertEquals("""{"reminder":null,"goalId":null}""", habitPatch(listOf(HabitChange.Reminder(null), HabitChange.GoalId(null))).toString())
        assertEquals("""{"days":[true,true,true,true,true,false,false]}""", habitPatch(listOf(HabitChange.Days(WEEKDAYS))).toString())
        assertEquals("{}", habitPatch(emptyList()).toString())
    }

    @Test fun everyKeyUsesItsWireValueAndTrimsText() {
        val body = habitPatch(
            listOf(
                HabitChange.Name("  Read  "), HabitChange.Type(HabitType.Duration), HabitChange.Target(20), HabitChange.Unit(" min "),
                HabitChange.Icon("book"), HabitChange.TimeOfDay(TimeOfDay.Morning), HabitChange.Reminder("07:00"),
                HabitChange.GoalId("g1"), HabitChange.Gentle(false), HabitChange.Archived(true),
            ),
        )
        assertEquals(
            """{"name":"Read","type":"duration","target":20,"unit":"min","icon":"book","timeOfDay":"morning","reminder":"07:00","goalId":"g1","gentle":false,"archived":true}""",
            body.toString(),
        )
        assertEquals(listOf(HabitChange.Name("Read"), HabitChange.Unit("min")), normalized(listOf(HabitChange.Name(" Read"), HabitChange.Unit("min "))))
    }
}
