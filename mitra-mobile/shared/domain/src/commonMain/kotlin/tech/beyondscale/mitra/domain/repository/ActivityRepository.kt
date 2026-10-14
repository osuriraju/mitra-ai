package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.ActivityItem

/** The server's read-only activity feed (`GET activity?limit=200`), cached for offline reading. */
interface ActivityRepository {
    /** Newest first. */
    val activity: Flow<List<ActivityItem>>

    suspend fun refresh(): Outcome<Unit>
}
