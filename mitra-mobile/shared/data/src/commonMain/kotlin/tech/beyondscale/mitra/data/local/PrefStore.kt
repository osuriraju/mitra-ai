package tech.beyondscale.mitra.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.data.db.MitraDb

object PrefKeys {
    const val THEME = "theme"
    const val LAST_USER_ID = "lastUserId"
}

/** Synchronous key/value access; callers on the main thread wrap calls in withContext(io). */
class PrefStore(private val db: MitraDb, private val io: CoroutineDispatcher) {
    fun get(key: String): String? = db.prefQueries.get(key).executeAsOneOrNull()
    fun put(key: String, value: String) = db.prefQueries.put(key, value)
    fun remove(key: String) = db.prefQueries.remove(key)
    fun observe(key: String): Flow<String?> = db.prefQueries.get(key).asFlow().mapToOneOrNull(io)
}
