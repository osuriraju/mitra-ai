package tech.beyondscale.mitra.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

const val SHARE_STOP_TIMEOUT_MS = 5_000L

/**
 * Shares one upstream query between all collectors. [SharingStarted.WhileSubscribed] keeps it alive for
 * [SHARE_STOP_TIMEOUT_MS] after the last collector leaves (config changes, quick tab hops). `replayExpirationMillis = 0`
 * drops the replay cache the moment upstream stops, so a later collector (new widget session, next account after
 * sign-out) never sees the previous account's value before the fresh query result.
 */
fun <T> Flow<T>.shareForRepository(scope: CoroutineScope): SharedFlow<T> =
    shareIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARE_STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        replay = 1,
    )
