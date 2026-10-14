package tech.beyondscale.mitra.feature.common

import kotlin.test.Test
import kotlin.test.assertEquals

class DatePickersTest {
    @Test fun roundTripsIsoDays() {
        assertEquals("2026-10-14", isoFromUtcMillis(utcMillisFromIso("2026-10-14")))
        assertEquals("1969-12-31", isoFromUtcMillis(-1))
    }

    @Test fun pickerMillisAreUtcMidnight() {
        assertEquals(86_400_000L, utcMillisFromIso("1970-01-02"))
        assertEquals("1970-01-02", isoFromUtcMillis(86_400_000L + 3_600_000L))
    }
}
