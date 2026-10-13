package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.SignupUseCase

data class SignupUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
)

sealed interface SignupAction {
    data class Name(val value: String) : SignupAction
    data class Email(val value: String) : SignupAction
    data class Password(val value: String) : SignupAction
    data object Submit : SignupAction
}

class SignupViewModel(private val signup: SignupUseCase) : ViewModel() {
    private val _state = MutableStateFlow(SignupUiState())
    val state: StateFlow<SignupUiState> = _state.asStateFlow()

    fun onAction(action: SignupAction) {
        when (action) {
            is SignupAction.Name -> _state.update { it.copy(name = action.value, fieldErrors = it.fieldErrors - "name", error = null) }
            is SignupAction.Email -> _state.update { it.copy(email = action.value, fieldErrors = it.fieldErrors - "email", error = null) }
            is SignupAction.Password -> _state.update { it.copy(password = action.value, fieldErrors = it.fieldErrors - "password", error = null) }
            SignupAction.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = signup(s.name, s.email, s.password)
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false)
                    is Outcome.Err -> it.copy(loading = false, fieldErrors = r.error.fieldErrors(), error = r.error.bannerText())
                }
            }
        }
    }
}
