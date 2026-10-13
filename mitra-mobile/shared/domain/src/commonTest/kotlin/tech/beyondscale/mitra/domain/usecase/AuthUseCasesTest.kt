package tech.beyondscale.mitra.domain.usecase

import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.testing.FakeAuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthUseCasesTest {
    private val repo = FakeAuthRepository()

    @Test fun loginRejectsBlankFieldsWithoutCallingServer() = runTest {
        val r = LoginUseCase(repo)("  ", "")
        val err = assertIs<AppError.Validation>(assertIs<Outcome.Err>(r).error)
        assertEquals(
            listOf(FieldIssue("identifier", "Enter your email or username"), FieldIssue("password", "Enter your password")),
            err.issues,
        )
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun loginTrimsIdentifier() = runTest {
        LoginUseCase(repo)("  raju@example.com ", "secret")
        assertEquals(listOf("login:raju@example.com"), repo.calls)
    }

    @Test fun signupValidatesLikeServer() = runTest {
        val r = SignupUseCase(repo)("", "not-an-email", "short")
        val issues = assertIs<AppError.Validation>(assertIs<Outcome.Err>(r).error).issues
        assertEquals(listOf("name", "email", "password"), issues.map { it.path })
        assertEquals("Password must be at least 8 characters", issues.last().message)
    }

    @Test fun signupNormalisesEmail() = runTest {
        SignupUseCase(repo)(" Raju ", " Raju@Example.COM ", "longenough")
        assertEquals(listOf("signup:Raju:raju@example.com"), repo.calls)
    }

    @Test fun forgotPasswordValidatesEmail() = runTest {
        assertIs<Outcome.Err>(ForgotPasswordUseCase(repo)("nope"))
        assertIs<Outcome.Ok<Unit>>(ForgotPasswordUseCase(repo)("raju@example.com"))
        assertEquals(listOf("forgot:raju@example.com"), repo.calls)
    }
}
