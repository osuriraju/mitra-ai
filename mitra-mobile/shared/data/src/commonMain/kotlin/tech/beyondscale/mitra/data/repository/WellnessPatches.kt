package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange

/** Blank notes are stored and sent as null (cleared), so the local row matches the server's echo. */
fun normalizedWellness(changes: List<WellnessChange>): List<WellnessChange> = changes.map { c ->
    if (c is WellnessChange.Note) WellnessChange.Note(c.v?.takeIf { it.isNotBlank() }) else c
}

/** `PUT wellness/entries/:date` body: only the changed keys; `null` clears a field. */
fun wellnessEntryPatch(changes: List<WellnessChange>): JsonObject = buildJsonObject {
    normalizedWellness(changes).forEach { c ->
        when (c) {
            is WellnessChange.Mood -> put("mood", c.v)
            is WellnessChange.SleepStart -> put("sleepStart", c.v)
            is WellnessChange.SleepEnd -> put("sleepEnd", c.v)
            is WellnessChange.Steps -> put("steps", c.v)
            is WellnessChange.Water -> put("water", c.v)
            is WellnessChange.Weight -> put("weight", c.v)
            is WellnessChange.Note -> put("note", c.v)
        }
    }
}

/**
 * `PATCH wellness/settings` body. Track toggles go out as one partial `track` object (the server merges it into the
 * stored map); the outbox's shallow coalesce would replace an earlier queued `track`, so callers' queued track keys
 * are merged in [WellnessRepositoryImpl] before enqueueing.
 */
fun wellnessSettingsPatch(changes: List<WellnessSettingsChange>, queuedTrack: JsonObject? = null): JsonObject = buildJsonObject {
    val track = changes.filterIsInstance<WellnessSettingsChange.Track>()
    changes.forEach { c ->
        when (c) {
            is WellnessSettingsChange.Height -> put("height", c.v)
            is WellnessSettingsChange.SleepTarget -> put("sleepTarget", c.v)
            is WellnessSettingsChange.StepsTarget -> put("stepsTarget", c.v)
            is WellnessSettingsChange.WaterTarget -> put("waterTarget", c.v)
            is WellnessSettingsChange.Track -> Unit
            is WellnessSettingsChange.ShareAI -> put("shareAI", c.v)
            is WellnessSettingsChange.ShowOnToday -> put("showOnToday", c.v)
            is WellnessSettingsChange.MorningReminder -> put("morningReminder", c.v)
            is WellnessSettingsChange.EveningReminder -> put("eveningReminder", c.v)
        }
    }
    if (track.isNotEmpty()) putJsonObject("track") {
        queuedTrack?.forEach { (k, v) -> put(k, v) }
        track.forEach { put(it.metric.wire, it.on) }
    }
}
