package tech.beyondscale.mitra.domain.model

/* Wellness models — wire shapes as mitra-backend/src/wellness/wellness.service.ts (entryOut / settingsOut). */

/** Mood 0..4 → emoji (web `MOODS`). */
val MOODS: List<String> = listOf("😞", "😕", "😐", "🙂", "😄")

/** One day's check-in. Every metric is optional; `savedAt` is the ISO day the row was first saved. */
data class WellnessEntry(
    val date: String,
    val mood: Int? = null,
    /** "HH:MM". */
    val sleepStart: String? = null,
    /** "HH:MM". */
    val sleepEnd: String? = null,
    val steps: Int? = null,
    /** Glasses. */
    val water: Int? = null,
    /** Kilograms. */
    val weight: Double? = null,
    val note: String? = null,
    val savedAt: String? = null,
)

enum class WellnessMetric(val wire: String) { Mood("mood"), Sleep("sleep"), Steps("steps"), Water("water"), Bmi("bmi") }

data class WellnessTrack(
    val mood: Boolean = true,
    val sleep: Boolean = true,
    val steps: Boolean = true,
    val water: Boolean = true,
    val bmi: Boolean = true,
) {
    operator fun get(m: WellnessMetric): Boolean = when (m) {
        WellnessMetric.Mood -> mood
        WellnessMetric.Sleep -> sleep
        WellnessMetric.Steps -> steps
        WellnessMetric.Water -> water
        WellnessMetric.Bmi -> bmi
    }

    fun with(m: WellnessMetric, on: Boolean): WellnessTrack = when (m) {
        WellnessMetric.Mood -> copy(mood = on)
        WellnessMetric.Sleep -> copy(sleep = on)
        WellnessMetric.Steps -> copy(steps = on)
        WellnessMetric.Water -> copy(water = on)
        WellnessMetric.Bmi -> copy(bmi = on)
    }
}

/** Defaults are the Prisma defaults (what `GET wellness/settings` returns for a new user). */
data class WellnessSettings(
    /** Centimetres, 50..272. */
    val height: Int = 170,
    /** Minutes, 60..900. */
    val sleepTarget: Int = 450,
    val stepsTarget: Int = 8000,
    val waterTarget: Int = 8,
    val track: WellnessTrack = WellnessTrack(),
    val shareAI: Boolean = false,
    val showOnToday: Boolean = true,
    val morningReminder: String = "07:30",
    val eveningReminder: String = "21:00",
)

/**
 * One field of `PUT wellness/entries/:date` (a merge-save: only the fields sent change, `null` clears).
 * Server limits: mood 0..4, steps 0..200000, water 0..100, weight 1..500, note ≤ 2000 chars, times "HH:MM".
 */
sealed interface WellnessChange {
    data class Mood(val v: Int?) : WellnessChange
    data class SleepStart(val v: String?) : WellnessChange
    data class SleepEnd(val v: String?) : WellnessChange
    data class Steps(val v: Int?) : WellnessChange
    data class Water(val v: Int?) : WellnessChange
    data class Weight(val v: Double?) : WellnessChange
    data class Note(val v: String?) : WellnessChange
}

/** One field of `PATCH wellness/settings`; [Track] merges into the stored map like the server. */
sealed interface WellnessSettingsChange {
    data class Height(val v: Int) : WellnessSettingsChange
    data class SleepTarget(val v: Int) : WellnessSettingsChange
    data class StepsTarget(val v: Int) : WellnessSettingsChange
    data class WaterTarget(val v: Int) : WellnessSettingsChange
    data class Track(val metric: WellnessMetric, val on: Boolean) : WellnessSettingsChange
    data class ShareAI(val v: Boolean) : WellnessSettingsChange
    data class ShowOnToday(val v: Boolean) : WellnessSettingsChange
    data class MorningReminder(val v: String) : WellnessSettingsChange
    data class EveningReminder(val v: String) : WellnessSettingsChange
}

/** The server's merge-save applied locally; a new row is stamped `savedAt = today`. */
fun WellnessEntry?.applyChanges(date: String, today: String, changes: List<WellnessChange>): WellnessEntry =
    changes.fold(this ?: WellnessEntry(date = date, savedAt = today)) { e, c ->
        when (c) {
            is WellnessChange.Mood -> e.copy(mood = c.v)
            is WellnessChange.SleepStart -> e.copy(sleepStart = c.v)
            is WellnessChange.SleepEnd -> e.copy(sleepEnd = c.v)
            is WellnessChange.Steps -> e.copy(steps = c.v)
            is WellnessChange.Water -> e.copy(water = c.v)
            is WellnessChange.Weight -> e.copy(weight = c.v)
            is WellnessChange.Note -> e.copy(note = c.v)
        }
    }

fun WellnessSettings.applyChanges(changes: List<WellnessSettingsChange>): WellnessSettings = changes.fold(this) { s, c ->
    when (c) {
        is WellnessSettingsChange.Height -> s.copy(height = c.v)
        is WellnessSettingsChange.SleepTarget -> s.copy(sleepTarget = c.v)
        is WellnessSettingsChange.StepsTarget -> s.copy(stepsTarget = c.v)
        is WellnessSettingsChange.WaterTarget -> s.copy(waterTarget = c.v)
        is WellnessSettingsChange.Track -> s.copy(track = s.track.with(c.metric, c.on))
        is WellnessSettingsChange.ShareAI -> s.copy(shareAI = c.v)
        is WellnessSettingsChange.ShowOnToday -> s.copy(showOnToday = c.v)
        is WellnessSettingsChange.MorningReminder -> s.copy(morningReminder = c.v)
        is WellnessSettingsChange.EveningReminder -> s.copy(eveningReminder = c.v)
    }
}
