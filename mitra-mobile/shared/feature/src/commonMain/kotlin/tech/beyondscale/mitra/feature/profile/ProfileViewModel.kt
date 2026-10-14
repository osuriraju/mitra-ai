package tech.beyondscale.mitra.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.habits.longestStreak
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.repository.AccountRepository
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository

/** Which form is busy / showing its error. */
enum class ProfileForm { Details, Password, Delete, Export }

data class ProfileUiState(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val initials: String = "",
    val memberSince: String = "",
    val tasksDone: Int = 0,
    val bestStreak: Int = 0,
    val goalsActive: Int = 0,
    val transactions: Int = 0,
    val moneyOn: Boolean = true,
    val busy: ProfileForm? = null,
    val error: Pair<ProfileForm, String>? = null,
)

sealed interface ProfileAction {
    data class SaveDetails(val name: String, val email: String, val phone: String) : ProfileAction
    data class ChangePassword(val current: String, val new: String) : ProfileAction
    data object Export : ProfileAction
    data class Delete(val confirmEmail: String, val password: String) : ProfileAction
    data object ClearError : ProfileAction
}

sealed interface ProfileEvent {
    /** The form succeeded: close its dialog and show [message]. */
    data class Done(val form: ProfileForm, val message: String) : ProfileEvent
    data class SaveFile(val fileName: String, val json: String) : ProfileEvent
}

/** Web `ProfileScreen`: details, password, export (JSON) and delete account (30-day grace). */
class ProfileViewModel(
    auth: AuthRepository,
    private val account: AccountRepository,
    tasks: TasksRepository,
    habits: HabitsRepository,
    goals: GoalsRepository,
    money: MoneyRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val ui = MutableStateFlow<Pair<ProfileForm?, Pair<ProfileForm, String>?>>(null to null)
    private val events = Channel<ProfileEvent>(Channel.BUFFERED)
    val effects: Flow<ProfileEvent> = events.receiveAsFlow()

    private data class Stats(val done: Int, val streak: Int, val goals: Int, val txs: Int)

    private val stats = combine(tasks.tasks, habits.habits, goals.goals, money.transactions, clock.dayFlow()) { t, h, g, x, today ->
        Stats(t.count { it.done }, longestStreak(h, today), g.count { it.state == GoalState.Active }, x.size)
    }

    val state: StateFlow<ProfileUiState> = combine(auth.session, stats, ui) { s, st, (busy, err) ->
        val u = (s as? SessionState.SignedIn)?.user
        ProfileUiState(
            u?.name.orEmpty(), u?.email.orEmpty(), u?.phone.orEmpty(), u?.initials.orEmpty(), u?.memberSince?.take(7).orEmpty(),
            st.done, st.streak, st.goals, st.txs, u?.settings?.modules?.money ?: true, busy, err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun onAction(a: ProfileAction) {
        when (a) {
            is ProfileAction.SaveDetails -> {
                if (a.name.isBlank()) return fail(ProfileForm.Details, "Enter your name")
                run(ProfileForm.Details, "Profile saved") { account.updateProfile(a.name, a.email, a.phone) }
            }
            is ProfileAction.ChangePassword -> {
                if (a.new.length < 8) return fail(ProfileForm.Password, "New password needs at least 8 characters")
                run(ProfileForm.Password, "Password changed · other devices signed out") { account.changePassword(a.current, a.new) }
            }
            ProfileAction.Export -> {
                if (ui.value.first != null) return
                ui.value = ProfileForm.Export to null
                viewModelScope.launch {
                    when (val r = account.exportData()) {
                        is Outcome.Ok -> { ui.value = null to null; events.send(ProfileEvent.SaveFile("mitra-export-${clock.today()}.json", r.value)) }
                        is Outcome.Err -> { ui.value = null to null; events.send(ProfileEvent.Done(ProfileForm.Export, r.error.message)) }
                    }
                }
            }
            is ProfileAction.Delete -> {
                if (a.confirmEmail.trim() != state.value.email || a.password.isEmpty()) return
                run(ProfileForm.Delete, "Account scheduled for deletion · sign in within 30 days to cancel") { account.deleteAccount(a.password) }
            }
            ProfileAction.ClearError -> ui.update { it.first to null }
        }
    }

    private fun fail(form: ProfileForm, message: String) { ui.update { it.first to (form to message) } }

    private fun run(form: ProfileForm, success: String, block: suspend () -> Outcome<*>) {
        if (ui.value.first != null) return
        ui.value = form to null
        viewModelScope.launch {
            when (val r = block()) {
                is Outcome.Ok -> { ui.value = null to null; events.send(ProfileEvent.Done(form, success)) }
                is Outcome.Err -> ui.value = null to (form to r.error.message)
            }
        }
    }
}
