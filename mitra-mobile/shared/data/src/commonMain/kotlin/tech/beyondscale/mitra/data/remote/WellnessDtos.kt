package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessTrack

/*
 * Shapes of mitra-backend/src/wellness/wellness.service.ts: `entryOut` omits null fields, `settingsOut` always sends
 * every key (track merged over all-true defaults).
 */

@Serializable
data class WellnessEntryDto(
    val mood: Int? = null,
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val steps: Int? = null,
    val water: Int? = null,
    val weight: Double? = null,
    val note: String? = null,
    val savedAt: String? = null,
)

@Serializable
data class WellnessTrackDto(
    val mood: Boolean = true,
    val sleep: Boolean = true,
    val steps: Boolean = true,
    val water: Boolean = true,
    val bmi: Boolean = true,
)

/** `height` has no default, so an unrelated object never decodes as settings. */
@Serializable
data class WellnessSettingsDto(
    val height: Int,
    val sleepTarget: Int = 450,
    val stepsTarget: Int = 8000,
    val waterTarget: Int = 8,
    val track: WellnessTrackDto = WellnessTrackDto(),
    val shareAI: Boolean = false,
    val showOnToday: Boolean = true,
    val morningReminder: String = "07:30",
    val eveningReminder: String = "21:00",
)

/** `GET wellness/bootstrap`. */
@Serializable
data class WellnessBootstrapDto(
    val entries: Map<String, WellnessEntryDto> = emptyMap(),
    val settings: WellnessSettingsDto,
)

/** `PUT wellness/entries/:date` answer. */
@Serializable
data class WellnessSaveDto(val date: String, val entry: WellnessEntryDto)

fun WellnessEntryDto.toDomain(date: String): WellnessEntry =
    WellnessEntry(date, mood, sleepStart, sleepEnd, steps, water, weight, note, savedAt)

fun WellnessSettingsDto.toDomain(): WellnessSettings = WellnessSettings(
    height = height, sleepTarget = sleepTarget, stepsTarget = stepsTarget, waterTarget = waterTarget,
    track = WellnessTrack(track.mood, track.sleep, track.steps, track.water, track.bmi),
    shareAI = shareAI, showOnToday = showOnToday, morningReminder = morningReminder, eveningReminder = eveningReminder,
)
