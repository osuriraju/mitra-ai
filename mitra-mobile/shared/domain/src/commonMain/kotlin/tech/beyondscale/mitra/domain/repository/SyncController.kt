package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tech.beyondscale.mitra.domain.model.SyncStatus

interface SyncController {
    val status: StateFlow<SyncStatus>
    val pendingCount: Flow<Long>
    /** User-facing messages, e.g. a change the server rejected. */
    val messages: Flow<String>
    fun requestSync()
}
