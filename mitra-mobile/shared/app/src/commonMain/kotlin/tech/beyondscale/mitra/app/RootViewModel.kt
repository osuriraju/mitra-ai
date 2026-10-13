package tech.beyondscale.mitra.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.components.PillTone
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SyncController

data class RootUiState(
    val session: SessionState = SessionState.Unknown,
    val theme: ThemeMode = ThemeMode.Dark,
    val sync: SyncStatus = SyncStatus.Idle,
)

class RootViewModel(auth: AuthRepository, prefs: PreferencesRepository, private val sync: SyncController) : ViewModel() {
    val state: StateFlow<RootUiState> = combine(auth.session, prefs.theme, sync.status, ::RootUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState())

    val messages: Flow<String> = sync.messages

    init { viewModelScope.launch { auth.restore() } }

    fun onForeground() = sync.requestSync()
}

/** Top-bar pill text; null when everything is synced. */
internal fun syncLabel(status: SyncStatus): Pair<String, PillTone>? = when (status) {
    SyncStatus.Idle -> null
    is SyncStatus.Syncing -> "Syncing…" to PillTone.Accent
    is SyncStatus.Offline -> "Offline · ${status.pending} pending" to PillTone.Warning
    is SyncStatus.Retrying -> "Waiting to sync · ${status.pending}" to PillTone.Neutral
}
