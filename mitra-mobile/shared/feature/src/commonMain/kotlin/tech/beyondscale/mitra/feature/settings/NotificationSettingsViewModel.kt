package tech.beyondscale.mitra.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.notify.NotificationPrefs
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository

/**
 * The web has no real notification list (its panel is sample data), so the notifications screen is its settings:
 * per-type reminder switches (device-local) and the account's quiet hours. Bills are hidden while Money is off.
 */
data class NotificationSettingsUiState(
    val prefs: NotificationPrefs = NotificationPrefs(),
    val quietHours: Boolean = true,
    val moneyOn: Boolean = true,
)

sealed interface NotificationSettingsAction {
    data class SetTasks(val on: Boolean) : NotificationSettingsAction
    data class SetHabits(val on: Boolean) : NotificationSettingsAction
    data class SetBills(val on: Boolean) : NotificationSettingsAction
    data class SetQuietHours(val on: Boolean) : NotificationSettingsAction
}

class NotificationSettingsViewModel(
    auth: AuthRepository,
    private val settings: SettingsRepository,
    private val prefs: PreferencesRepository,
) : ViewModel() {
    val state: StateFlow<NotificationSettingsUiState> = combine(auth.session, prefs.notificationPrefs) { session, p ->
        val s = (session as? SessionState.SignedIn)?.user?.settings
        NotificationSettingsUiState(p, s?.quietHours ?: true, s?.modules?.money ?: true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUiState())

    fun onAction(action: NotificationSettingsAction) {
        viewModelScope.launch {
            // Read-modify-write against the stored value, so quick toggles of two switches never drop each other.
            val p = prefs.notificationPrefs.first()
            when (action) {
                is NotificationSettingsAction.SetTasks -> prefs.setNotificationPrefs(p.copy(tasks = action.on))
                is NotificationSettingsAction.SetHabits -> prefs.setNotificationPrefs(p.copy(habits = action.on))
                is NotificationSettingsAction.SetBills -> prefs.setNotificationPrefs(p.copy(bills = action.on))
                is NotificationSettingsAction.SetQuietHours -> settings.setQuietHours(action.on)
            }
        }
    }
}
