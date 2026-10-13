package tech.beyondscale.mitra.feature.auth

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.SignupUseCase
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SignupViewModelTest : ViewModelTest() {
    private val repo = FakeAuthRepository()

    @Test fun serverEmailTakenShowsAsBanner() {
        repo.result = Outcome.Err(AppError.Server(409, "EMAIL_TAKEN", "An account with this email already exists"))
        val vm = SignupViewModel(SignupUseCase(repo))
        vm.onAction(SignupAction.Name("Raju")); vm.onAction(SignupAction.Email("raju@example.com")); vm.onAction(SignupAction.Password("longenough"))
        vm.onAction(SignupAction.Submit)
        assertEquals("An account with this email already exists", vm.state.value.error)
    }

    @Test fun shortPasswordIsFieldError() {
        val vm = SignupViewModel(SignupUseCase(repo))
        vm.onAction(SignupAction.Name("Raju")); vm.onAction(SignupAction.Email("raju@example.com")); vm.onAction(SignupAction.Password("short"))
        vm.onAction(SignupAction.Submit)
        assertEquals(mapOf("password" to "Password must be at least 8 characters"), vm.state.value.fieldErrors)
    }
}
