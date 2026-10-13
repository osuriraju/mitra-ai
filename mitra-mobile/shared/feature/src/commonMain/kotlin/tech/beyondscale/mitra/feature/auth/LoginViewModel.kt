package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.LoginUseCase

data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
)

sealed interface LoginAction {
    data class Identifier(val value: String) : LoginAction
    data class Password(val value: String) : LoginAction
    data object Submit : LoginAction
}

internal fun AppError.fieldErrors(): Map<String, String> =
    (this as? AppError.Validation)?.issues?.associate { it.path to it.message }.orEmpty()

/** Validation errors sit under their fields; everything else shows as a banner. */
internal fun AppError.bannerText(): String? = if (this is AppError.Validation) null else message

/** On success the app root sees SessionState.SignedIn and swaps to the main shell — no navigation here. */
class LoginViewModel(private val login: LoginUseCase) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.Identifier -> _state.update { it.copy(identifier = action.value, fieldErrors = it.fieldErrors - "identifier", error = null) }
            is LoginAction.Password -> _state.update { it.copy(password = action.value, fieldErrors = it.fieldErrors - "password", error = null) }
            LoginAction.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = login(s.identifier, s.password)
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false)
                    is Outcome.Err -> it.copy(loading = false, fieldErrors = r.error.fieldErrors(), error = r.error.bannerText())
                }
            }
        }
    }
}
