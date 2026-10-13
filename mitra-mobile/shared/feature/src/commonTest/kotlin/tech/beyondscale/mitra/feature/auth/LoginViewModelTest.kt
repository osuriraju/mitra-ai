package tech.beyondscale.mitra.feature.auth

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.LoginUseCase
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LoginViewModelTest : ViewModelTest() {
    private val repo = FakeAuthRepository()
    private fun vm() = LoginViewModel(LoginUseCase(repo))

    @Test fun emptySubmitShowsFieldErrors() {
        val vm = vm()
        vm.onAction(LoginAction.Submit)
        assertEquals(setOf("identifier", "password"), vm.state.value.fieldErrors.keys)
        assertFalse(vm.state.value.loading)
    }

    @Test fun typingClearsThatFieldsError() {
        val vm = vm()
        vm.onAction(LoginAction.Submit)
        vm.onAction(LoginAction.Identifier("raju"))
        assertEquals(setOf("password"), vm.state.value.fieldErrors.keys)
    }

    @Test fun wrongPasswordShowsServerMessage() {
        repo.result = Outcome.Err(AppError.Unauthenticated("BAD_CREDENTIALS", "Email/username or password is incorrect"))
        val vm = vm()
        vm.onAction(LoginAction.Identifier("raju")); vm.onAction(LoginAction.Password("nope")); vm.onAction(LoginAction.Submit)
        assertEquals("Email/username or password is incorrect", vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun offlineShowsOfflineMessage() {
        repo.result = Outcome.Err(AppError.Offline)
        val vm = vm()
        vm.onAction(LoginAction.Identifier("raju")); vm.onAction(LoginAction.Password("pw")); vm.onAction(LoginAction.Submit)
        assertEquals("You appear to be offline", vm.state.value.error)
    }

    @Test fun successCallsRepository() {
        val vm = vm()
        vm.onAction(LoginAction.Identifier("raju")); vm.onAction(LoginAction.Password("pw")); vm.onAction(LoginAction.Submit)
        assertEquals(listOf("login:raju"), repo.calls)
        assertNull(vm.state.value.error)
    }
}
