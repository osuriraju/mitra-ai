package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitDtosTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test fun decodesOptionalFields() {
        val h = json.decodeFromString(
            HabitDto.serializer(),
            """{"id":"h1","name":"Read","type":"juggling","target":1,"unit":"","icon":"book","timeOfDay":"morning","days":[true,true,true,true,true,false,false],"gentle":true,"createdAt":"2026-10-01T04:30:00.000Z","logs":{"2026-10-13":1}}""",
        ).toDomain()
        assertNull(h.reminder)
        assertNull(h.goalId)
        assertFalse(h.archived)
        assertEquals(mapOf("2026-10-13" to 1), h.logs)
        assertEquals(HabitType.Binary, h.type)
        assertEquals(TimeOfDay.Morning, h.timeOfDay)
        assertEquals(WEEKDAYS, h.days)

        val full = json.decodeFromString(
            HabitDto.serializer(),
            """{"id":"h2","name":"Walk","type":"count","target":5,"unit":"km","icon":"walk","timeOfDay":"evening","days":[true],"reminder":"07:30","goalId":"g1","gentle":false,"archived":true,"createdAt":"x"}""",
        ).toDomain()
        assertEquals("07:30", full.reminder)
        assertEquals("g1", full.goalId)
        assertTrue(full.archived)
        assertEquals(ALL_DAYS, full.days) // malformed days read as every day
        assertEquals(emptyMap(), full.logs)
    }

    @Test fun okBodyIsNotAHabit() {
        assertTrue(runCatching { json.decodeFromString(HabitDto.serializer(), """{"ok":true}""") }.isFailure)
    }

    @Test fun createBodySendsEveryFieldWithNulls() {
        val h = Habit(
            "h1", "Read", HabitType.Count, 10, "pages", "book", TimeOfDay.Evening, WEEKDAYS, null, null,
            gentle = true, archived = false, createdAt = "2026-10-14T04:30:00Z", logs = mapOf("2026-10-13" to 3),
        )
        assertEquals(
            """{"id":"h1","name":"Read","type":"count","target":10,"unit":"pages","icon":"book","timeOfDay":"evening","days":[true,true,true,true,true,false,false],"reminder":null,"goalId":null,"gentle":true}""",
            h.createBody().toString(),
        )
    }
}
