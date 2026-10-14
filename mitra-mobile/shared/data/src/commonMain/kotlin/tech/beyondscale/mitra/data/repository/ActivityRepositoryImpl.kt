package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ActivityDto
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.domain.model.ActivityItem
import tech.beyondscale.mitra.domain.repository.ActivityRepository

/** Read-only server feed (`GET activity?limit=200`); each refresh replaces the cached copy. */
class ActivityRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ActivityRepository {
    override val activity: Flow<List<ActivityItem>> =
        db.activityQueries.selectAll().asFlow().mapToList(io).map { l -> l.map { it.toDomain() } }.shareForRepository(scope)

    override suspend fun refresh(): Outcome<Unit> {
        val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
        val body = when (val r = api.call<List<ActivityDto>>(HttpMethod.Get, "activity?limit=200")) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        withContext(io) {
            db.transaction {
                if (prefs.get(PrefKeys.LAST_USER_ID) != owner) return@transaction
                db.activityQueries.deleteAll()
                body.forEach { db.activityQueries.upsert(it.toDomain().toRow()) }
            }
        }
        return Outcome.Ok(Unit)
    }
}
