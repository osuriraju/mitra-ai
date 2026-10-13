package tech.beyondscale.mitra.feature.auth

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgotPasswordViewModelTest : ViewModelTest() {
    @Test fun sendsAndShowsConfirmation() {
        val repo = FakeAuthRepository()
        val vm = ForgotPasswordViewModel(ForgotPasswordUseCase(repo))
        vm.onAction(ForgotAction.Email("raju@example.com")); vm.onAction(ForgotAction.Submit)
        assertTrue(vm.state.value.sent)
        assertEquals(listOf("forgot:raju@example.com"), repo.calls)
    }

    @Test fun invalidEmailStaysOnForm() {
        val vm = ForgotPasswordViewModel(ForgotPasswordUseCase(FakeAuthRepository()))
        vm.onAction(ForgotAction.Email("nope")); vm.onAction(ForgotAction.Submit)
        assertFalse(vm.state.value.sent)
        assertEquals("Enter a valid email", vm.state.value.fieldError)
    }

    @Test fun unmappedValidationIssueShowsAsBanner() {
        val repo = FakeAuthRepository().apply { forgotResult = Outcome.Err(AppError.Validation(listOf(FieldIssue("root", "Too many requests")))) }
        val vm = ForgotPasswordViewModel(ForgotPasswordUseCase(repo))
        vm.onAction(ForgotAction.Email("raju@example.com")); vm.onAction(ForgotAction.Submit)
        assertEquals("Too many requests", vm.state.value.error)
        assertFalse(vm.state.value.sent)
    }
}
