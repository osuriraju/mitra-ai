package tech.beyondscale.mitra.feature.tasks

import kotlinx.coroutines.flow.Flow

/**
 * Platform hook that keeps a running focus countdown alive while the app is in the background
 * (Android: a foreground service with an ongoing countdown notification and a Stop action).
 * Optional: without one bound in Koin, the countdown pauses when the screen stops.
 */
interface FocusKeepAlive {
    /** Show / update the ongoing notification; the countdown ends at [endsAtEpochMs]. */
    fun start(title: String, endsAtEpochMs: Long)
    fun stop()
    /** The user tapped Stop on the notification. */
    val stopRequests: Flow<Unit>
}
