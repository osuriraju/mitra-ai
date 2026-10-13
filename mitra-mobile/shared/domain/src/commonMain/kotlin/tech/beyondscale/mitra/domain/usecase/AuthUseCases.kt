package tech.beyondscale.mitra.domain.usecase

import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository

private fun invalid(issues: List<FieldIssue>) = Outcome.Err(AppError.Validation(issues))

class LoginUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(identifier: String, password: String): Outcome<User> {
        val issues = AuthValidation.login(identifier, password)
        return if (issues.isNotEmpty()) invalid(issues) else repo.login(identifier.trim(), password)
    }
}

class SignupUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(name: String, email: String, password: String): Outcome<User> {
        val issues = AuthValidation.signup(name, email, password)
        return if (issues.isNotEmpty()) invalid(issues) else repo.signup(name.trim(), email.trim().lowercase(), password)
    }
}

class ForgotPasswordUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(email: String): Outcome<Unit> {
        val issues = AuthValidation.email(email)
        return if (issues.isNotEmpty()) invalid(issues) else repo.forgotPassword(email.trim().lowercase())
    }
}
