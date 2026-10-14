package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.ActivityItem
import tech.beyondscale.mitra.domain.repository.ActivityRepository

class FakeActivityRepository(initial: List<ActivityItem> = emptyList()) : ActivityRepository {
    val activityFlow = MutableStateFlow(initial)
    override val activity: Flow<List<ActivityItem>> = activityFlow
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    var refreshes = 0

    override suspend fun refresh(): Outcome<Unit> { refreshes++; return refreshResult }
}
