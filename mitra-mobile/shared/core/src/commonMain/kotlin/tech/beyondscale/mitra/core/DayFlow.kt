package tech.beyondscale.mitra.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/** Epoch millis of the next local midnight (start of tomorrow in [AppClock.zone]). */
fun AppClock.nextMidnightEpochMs(): Long =
    localNow().date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()

/** The local date now, and again whenever it changes at local midnight — keeps "today" fresh without a restart. */
fun AppClock.dayFlow(): Flow<String> = flow {
    while (true) {
        emit(today())
        delay((nextMidnightEpochMs() - now().toEpochMilliseconds()).coerceAtLeast(0) + 1)
    }
}.distinctUntilChanged()
