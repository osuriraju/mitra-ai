package tech.beyondscale.mitra.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController

data class SettingsUiState(
    val name: String = "",
    val email: String = "",
    val initials: String = "",
    val theme: ThemeMode = ThemeMode.Dark,
    val modules: Modules = Modules(),
    val pending: Long = 0,
    val signingOut: Boolean = false,
)

enum class ModuleKey { Money, Wellness, Ai }

sealed interface SettingsAction {
    data class SetTheme(val mode: ThemeMode) : SettingsAction
    data class SetModule(val key: ModuleKey, val on: Boolean) : SettingsAction
    data object SignOut : SettingsAction
}

class SettingsViewModel(
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val prefs: PreferencesRepository,
    sync: SyncController,
) : ViewModel() {
    private val signingOut = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(auth.session, prefs.theme, sync.pendingCount, signingOut) { session, theme, pending, out ->
        val user = (session as? SessionState.SignedIn)?.user
        SettingsUiState(user?.name.orEmpty(), user?.email.orEmpty(), user?.initials.orEmpty(), theme, user?.settings?.modules ?: Modules(), pending, out)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetTheme -> viewModelScope.launch { prefs.setTheme(action.mode) }
            is SettingsAction.SetModule -> viewModelScope.launch {
                val current = (auth.session.value as? SessionState.SignedIn)?.user?.settings?.modules ?: return@launch
                settings.updateModules(
                    when (action.key) {
                        ModuleKey.Money -> current.copy(money = action.on)
                        ModuleKey.Wellness -> current.copy(wellness = action.on)
                        ModuleKey.Ai -> current.copy(ai = action.on)
                    },
                )
            }
            SettingsAction.SignOut -> {
                signingOut.value = true
                viewModelScope.launch { auth.logout() }
            }
        }
    }
}
