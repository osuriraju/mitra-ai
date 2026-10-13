package tech.beyondscale.mitra.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

/** What a failed submit shows: messages under the visible fields, plus a banner for anything else. */
internal data class ErrorPresentation(val fieldErrors: Map<String, String>, val banner: String?)

/**
 * Validation issues whose path is a rendered field go under that field. If any issue has no field to sit
 * under (or none map at all), the user would otherwise see nothing, so a banner is shown as well.
 * Non-validation errors are always a banner.
 */
internal fun AppError.present(visibleFields: Set<String>): ErrorPresentation {
    if (this !is AppError.Validation) return ErrorPresentation(emptyMap(), message)
    val (mapped, unmapped) = issues.partition { it.path in visibleFields }
    val fields = mapped.associate { it.path to it.message }
    val banner = if (mapped.isEmpty() || unmapped.isNotEmpty()) unmapped.firstOrNull()?.message ?: message else null
    return ErrorPresentation(fields, banner)
}

/** Runs a use case so that a throwing one can never leave the form stuck loading. */
internal suspend fun <T> guarded(block: suspend () -> Outcome<T>): Outcome<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(AppError.Unknown(e))
    }

/** On success the app root sees SessionState.SignedIn and swaps to the main shell — no navigation here. */
class LoginViewModel(private val login: LoginUseCase) : ViewModel() {
    private companion object { val FIELDS = setOf("identifier", "password") }

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
            val r = guarded { login(s.identifier, s.password) }
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(loading = false)
                    is Outcome.Err -> r.error.present(FIELDS).let { p -> it.copy(loading = false, fieldErrors = p.fieldErrors, error = p.banner) }
                }
            }
        }
    }
}
