package tech.beyondscale.mitra.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Platform hook for the periodic sync job (Android: 15-minute WorkManager job that needs a network). */
fun interface PeriodicSync { fun setEnabled(enabled: Boolean) }

/** Keeps the periodic job scheduled exactly while the outbox has rows (spec §6 triggers). */
class PeriodicSyncController(pending: Flow<Long>, periodic: PeriodicSync, scope: CoroutineScope) {
    init {
        scope.launch { pending.map { it > 0 }.distinctUntilChanged().collect { periodic.setEnabled(it) } }
    }
}
