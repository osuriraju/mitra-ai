package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange

/** Wellness check-ins and settings, offline-first: writes apply locally at once and sync through the outbox. */
interface WellnessRepository {
    /** Entries keyed by ISO day (the server's default window is the last 120 days). */
    val entries: Flow<Map<String, WellnessEntry>>

    /** Server defaults until the first refresh. */
    val settings: Flow<WellnessSettings>

    /** Re-reads `wellness/bootstrap`; days and settings with queued changes keep their local values. */
    suspend fun refresh(): Outcome<Unit>

    /** Merge-saves [date]'s entry (`PUT wellness/entries/:date`); patches for one day coalesce in the outbox. */
    suspend fun saveEntry(date: String, changes: List<WellnessChange>)

    /**
     * `PATCH wellness/settings`. Toggling share-with-AI on the web also sets the user setting `shareWellness`
     * (callers do that through [SettingsRepository]).
     */
    suspend fun updateSettings(changes: List<WellnessSettingsChange>)

    companion object {
        const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED
    }
}
