package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase

data class ForgotUiState(
    val email: String = "",
    val loading: Boolean = false,
    val sent: Boolean = false,
    val fieldError: String? = null,
    val error: String? = null,
)

sealed interface ForgotAction {
    data class Email(val value: String) : ForgotAction
    data object Submit : ForgotAction
}

class ForgotPasswordViewModel(private val forgot: ForgotPasswordUseCase) : ViewModel() {
    private companion object { val FIELDS = setOf("email") }

    private val _state = MutableStateFlow(ForgotUiState())
    val state: StateFlow<ForgotUiState> = _state.asStateFlow()

    fun onAction(action: ForgotAction) {
        when (action) {
            is ForgotAction.Email -> _state.update { it.copy(email = action.value, fieldError = null, error = null) }
            ForgotAction.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = guarded { forgot(s.email) }
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false, sent = true)
                    is Outcome.Err -> r.error.present(FIELDS).let { p -> it.copy(loading = false, fieldError = p.fieldErrors["email"], error = p.banner) }
                }
            }
        }
    }
}
