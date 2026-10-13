package tech.beyondscale.mitra.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.UserEnvelope
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository

/** Account settings live in the user's JSONB `settings`; PATCH /users/me/settings shallow-merges. */
class SettingsRepositoryImpl(
    private val db: MitraDb,
    private val cache: UserCache,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val auth: AuthRepository,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    init {
        engine.register(ENTITY, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val user = response?.let { runCatching { json.decodeFromJsonElement(UserEnvelope.serializer(), it).user }.getOrNull() } ?: return
                withContext(io) {
                    // A newer local change is still queued: keep it rather than flashing the older server copy.
                    if (db.outboxQueries.pendingByCoalesceKey(ENTITY).executeAsOneOrNull() == null) cache.put(user)
                }
            }
            override suspend fun onRejected(error: AppError) { auth.refresh() }
        })
    }

    override suspend fun updateModules(modules: Modules) {
        val patch = buildJsonObject {
            putJsonObject("modules") { put("money", modules.money); put("wellness", modules.wellness); put("ai", modules.ai) }
        }
        withContext(io) {
            db.transaction {
                val dto = cache.current() ?: return@transaction
                cache.put(dto.copy(settings = JsonObject(dto.settings + patch)))
                outbox.enqueue(ENTITY, "PATCH", "users/me/settings", patch, coalesceKey = ENTITY)
            }
        }
        engine.requestSync()
    }

    companion object { const val ENTITY = "user-settings" }
}
