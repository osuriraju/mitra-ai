package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.WellnessBootstrapDto
import tech.beyondscale.mitra.data.remote.WellnessSaveDto
import tech.beyondscale.mitra.data.remote.WellnessSettingsDto
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.repository.WellnessRepository

/**
 * Wellness check-ins (one row per day) and the single settings row, offline-first (the `HabitsRepositoryImpl`
 * skeleton). A day with a queued `PUT wellness/entries/:date` (`entityId = date`) and the settings while any
 * `PATCH wellness/settings` is queued keep their local values: no echo or refresh overwrites them.
 */
class WellnessRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WellnessRepository {
    /** Bumped before every local write and every applied server echo — see `TasksRepositoryImpl.writes`. */
    private val writes = MutableStateFlow(0L)

    override val entries: Flow<Map<String, WellnessEntry>> =
        db.wellnessEntriesQueries.selectAll().asFlow().mapToList(io)
            .map { rows -> rows.associate { it.date to it.toDomain() } }
            .shareForRepository(scope)

    override val settings: Flow<WellnessSettings> =
        db.wellnessSettingsQueries.select().asFlow().mapToOneOrNull(io)
            .map { it?.toDomain() ?: WellnessSettings() }
            .shareForRepository(scope)

    init {
        engine.register(WELLNESS_ENTRY, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(response, WellnessSaveDto.serializer()) ?: return
                writes.update { it + 1 }
                withContext(io) {
                    db.transaction {
                        if (dto.date !in pendingDates()) db.wellnessEntriesQueries.upsert(dto.entry.toDomain(dto.date).toRow())
                    }
                }
            }
            // e.g. a 400 the form let through: the server copy decides.
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(WELLNESS_SETTINGS, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(response, WellnessSettingsDto.serializer()) ?: return
                writes.update { it + 1 }
                withContext(io) {
                    db.transaction { if (!settingsPending()) db.wellnessSettingsQueries.upsert(dto.toDomain().toRow()) }
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<WellnessBootstrapDto>(HttpMethod.Get, "wellness/bootstrap")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val windowStart = addDays(clock.today(), -WINDOW_DAYS)
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { apply(body, windowStart); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        return Outcome.Err(AppError.Server(409, WellnessRepository.REFRESH_SUPERSEDED, "Your changes are still syncing. Try again in a moment."))
    }

    /**
     * Server copy → local tables. The server only sends its last 120 days, so older local days are never dropped for
     * being absent; days and settings with queued writes are kept. In a transaction.
     */
    private fun apply(body: WellnessBootstrapDto, windowStart: String) {
        val pending = pendingDates()
        db.wellnessEntriesQueries.selectDates().executeAsList()
            .filter { it >= windowStart && it !in body.entries && it !in pending }
            .forEach { db.wellnessEntriesQueries.delete(it) }
        body.entries.forEach { (date, dto) ->
            if (date !in pending) db.wellnessEntriesQueries.upsert(dto.toDomain(date).toRow())
        }
        if (!settingsPending()) db.wellnessSettingsQueries.upsert(body.settings.toDomain().toRow())
    }

    /* ---------- writes ---------- */

    override suspend fun saveEntry(date: String, changes: List<WellnessChange>) {
        if (changes.isEmpty()) return
        val clean = normalizedWellness(changes)
        val today = clock.today()
        write {
            val current = db.wellnessEntriesQueries.selectByDate(date).executeAsOneOrNull()?.toDomain()
            db.wellnessEntriesQueries.upsert(current.applyChanges(date, today, clean).toRow())
            outbox.enqueue(
                WELLNESS_ENTRY, "PUT", "wellness/entries/$date", wellnessEntryPatch(clean),
                coalesceKey = "$WELLNESS_ENTRY:$date", entityId = date,
            )
        }
    }

    override suspend fun updateSettings(changes: List<WellnessSettingsChange>) {
        if (changes.isEmpty()) return
        write {
            val current = db.wellnessSettingsQueries.select().executeAsOneOrNull()?.toDomain() ?: WellnessSettings()
            db.wellnessSettingsQueries.upsert(current.applyChanges(changes).toRow())
            // The writer's coalesce is shallow: carry a queued row's `track` keys so a second toggle doesn't drop the first.
            val queuedTrack = db.outboxQueries.pendingByCoalesceKey(WELLNESS_SETTINGS).executeAsOneOrNull()?.body
                ?.let { runCatching { api.json.parseToJsonElement(it).jsonObject["track"] as? JsonObject }.getOrNull() }
            outbox.enqueue(WELLNESS_SETTINGS, "PATCH", "wellness/settings", wellnessSettingsPatch(changes, queuedTrack), coalesceKey = WELLNESS_SETTINGS)
        }
    }

    /* ---------- helpers ---------- */

    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    private fun pendingDates(): Set<String> =
        db.outboxQueries.pendingEntityIds(WELLNESS_ENTRY).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun settingsPending(): Boolean = db.outboxQueries.selectAll().executeAsList().any { it.entity == WELLNESS_SETTINGS }

    private fun <T> decode(element: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    companion object {
        const val WELLNESS_ENTRY = OutboxEntities.WELLNESS_ENTRY
        const val WELLNESS_SETTINGS = OutboxEntities.WELLNESS_SETTINGS
        private const val REFRESH_ATTEMPTS = 3
        /** The server sends 120 days by its UTC date; two days less covers the device's local day. */
        private const val WINDOW_DAYS = 118
    }
}
