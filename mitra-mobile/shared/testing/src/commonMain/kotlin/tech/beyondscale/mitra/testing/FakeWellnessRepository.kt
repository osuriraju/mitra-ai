package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.repository.WellnessRepository

/** In-memory [WellnessRepository]; a new day is stamped `savedAt = date`. Writes are recorded in [calls]. */
class FakeWellnessRepository(
    initial: Map<String, WellnessEntry> = emptyMap(),
    settings: WellnessSettings = WellnessSettings(),
) : WellnessRepository {
    val entryFlow = MutableStateFlow(initial)
    val settingsFlow = MutableStateFlow(settings)
    override val entries: Flow<Map<String, WellnessEntry>> = entryFlow
    override val settings: Flow<WellnessSettings> = settingsFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun saveEntry(date: String, changes: List<WellnessChange>) {
        calls += "save:$date"
        entryFlow.update { it + (date to it[date].applyChanges(date, date, changes)) }
    }

    override suspend fun updateSettings(changes: List<WellnessSettingsChange>) {
        calls += "settings"
        settingsFlow.update { it.applyChanges(changes) }
    }
}
