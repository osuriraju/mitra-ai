package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tech.beyondscale.mitra.domain.model.SyncStatus

interface SyncController {
    val status: StateFlow<SyncStatus>
    val pendingCount: Flow<Long>
    /**
     * User-facing messages, e.g. a change the server rejected. Buffered until collected, but single-consumer
     * (backed by a Channel): each message reaches only one collector, so collect it in exactly one place (the shell).
     */
    val messages: Flow<String>
    fun requestSync()
}
