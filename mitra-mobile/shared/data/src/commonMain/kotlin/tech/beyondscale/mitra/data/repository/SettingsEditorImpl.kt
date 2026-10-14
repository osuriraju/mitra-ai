package tech.beyondscale.mitra.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.domain.repository.SettingChange
import tech.beyondscale.mitra.domain.repository.SettingsEditor

/**
 * Same path as [SettingsRepositoryImpl]: the patch is applied to the cached user and queued under the user-settings
 * entity / coalesce key, so its sync handler (registered there) acknowledges it.
 */
class SettingsEditorImpl(
    private val db: MitraDb,
    private val cache: UserCache,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SettingsEditor {
    override suspend fun update(change: SettingChange) {
        val patch = settingPatch(change)
        withContext(io) {
            db.transaction {
                val dto = cache.current() ?: return@transaction
                cache.put(dto.copy(settings = JsonObject(dto.settings + patch)))
                outbox.enqueue(ENTITY, "PATCH", "users/me/settings", patch, coalesceKey = ENTITY)
            }
        }
        engine.requestSync()
    }

    private companion object { const val ENTITY = OutboxEntities.USER_SETTINGS }
}

internal fun settingPatch(change: SettingChange): JsonObject = buildJsonObject {
    when (change) {
        is SettingChange.TodaySections -> putJsonArray("todaySections") { change.keys.forEach { add(it) } }
        is SettingChange.WeekStart -> put("weekStart", change.day)
        is SettingChange.DayStart -> put("dayStart", change.hhmm)
        is SettingChange.Digest -> put("digest", change.on)
        is SettingChange.AutoSave -> put("autoSave", change.mode)
        is SettingChange.FocusMinutes -> put("focusMinutes", change.minutes)
    }
}
