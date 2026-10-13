package tech.beyondscale.mitra.domain.usecase

import tech.beyondscale.mitra.core.FieldIssue

/** Client-side copy of mitra-backend/src/auth/schemas.ts so the form reacts instantly; the server still validates. */
internal object AuthValidation {
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun login(identifier: String, password: String) = buildList {
        if (identifier.isBlank()) add(FieldIssue("identifier", "Enter your email or username"))
        if (password.isEmpty()) add(FieldIssue("password", "Enter your password"))
    }

    fun signup(name: String, email: String, password: String) = buildList {
        if (name.isBlank()) add(FieldIssue("name", "Enter your name"))
        addAll(email(email))
        if (password.length < 8) add(FieldIssue("password", "Password must be at least 8 characters"))
    }

    fun email(email: String) = buildList {
        if (!EMAIL.matches(email.trim())) add(FieldIssue("email", "Enter a valid email"))
    }
}
