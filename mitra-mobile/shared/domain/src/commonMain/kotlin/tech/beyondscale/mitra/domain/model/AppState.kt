package tech.beyondscale.mitra.domain.model

sealed interface SessionState {
    /** Still reading the local cache / asking the server. */
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: User) : SessionState
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Syncing(val pending: Long) : SyncStatus
    /** No network; changes are queued and will go out when it returns. */
    data class Offline(val pending: Long) : SyncStatus
    /** Server error (5xx/429); retrying with backoff. */
    data class Retrying(val pending: Long) : SyncStatus
}

enum class ThemeMode { Dark, Light, Auto }
