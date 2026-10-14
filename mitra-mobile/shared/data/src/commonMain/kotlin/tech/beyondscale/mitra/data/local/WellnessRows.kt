package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Wellness_entries
import tech.beyondscale.mitra.data.db.Wellness_settings
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessTrack

/** The only `wellness_settings` row id. */
const val WELLNESS_SETTINGS_ID = 1L

private fun Boolean.bit(): Long = if (this) 1L else 0L

fun Wellness_entries.toDomain(): WellnessEntry = WellnessEntry(
    date, mood?.toInt(), sleepStart, sleepEnd, steps?.toInt(), water?.toInt(), weight, note, savedAt,
)

fun WellnessEntry.toRow(): Wellness_entries = Wellness_entries(
    date, mood?.toLong(), sleepStart, sleepEnd, steps?.toLong(), water?.toLong(), weight, note, savedAt,
)

fun Wellness_settings.toDomain(): WellnessSettings = WellnessSettings(
    height = height.toInt(), sleepTarget = sleepTarget.toInt(), stepsTarget = stepsTarget.toInt(), waterTarget = waterTarget.toInt(),
    track = WellnessTrack(trackMood != 0L, trackSleep != 0L, trackSteps != 0L, trackWater != 0L, trackBmi != 0L),
    shareAI = shareAI != 0L, showOnToday = showOnToday != 0L, morningReminder = morningReminder, eveningReminder = eveningReminder,
)

fun WellnessSettings.toRow(): Wellness_settings = Wellness_settings(
    WELLNESS_SETTINGS_ID, height.toLong(), sleepTarget.toLong(), stepsTarget.toLong(), waterTarget.toLong(),
    track.mood.bit(), track.sleep.bit(), track.steps.bit(), track.water.bit(), track.bmi.bit(),
    shareAI.bit(), showOnToday.bit(), morningReminder, eveningReminder,
)
