package tech.beyondscale.mitra.core

data class FieldIssue(val path: String, val message: String)

/** Every failure the UI can see. API errors (`{ error: { code, message, issues? } }`) map onto these. */
sealed interface AppError {
    val message: String

    data object Offline : AppError {
        override val message = "You appear to be offline"
    }

    /** 401. `code` is UNAUTHENTICATED for an expired session, BAD_CREDENTIALS for a wrong password. */
    data class Unauthenticated(val code: String = "UNAUTHENTICATED", override val message: String = "Please sign in") : AppError

    data class Validation(
        val issues: List<FieldIssue>,
        override val message: String = issues.firstOrNull()?.message ?: "Please check the form",
    ) : AppError

    data class Server(val status: Int, val code: String, override val message: String) : AppError

    data class Unknown(val cause: Throwable) : AppError {
        override val message = "Something went wrong"
    }
}
