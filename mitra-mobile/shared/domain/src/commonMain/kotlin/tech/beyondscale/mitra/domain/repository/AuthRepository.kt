package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.StateFlow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.User

interface AuthRepository {
    val session: StateFlow<SessionState>
    /** On launch: show the cached user immediately (offline-first), then confirm with the server. */
    suspend fun restore()
    suspend fun refresh(): Outcome<User>
    suspend fun login(identifier: String, password: String): Outcome<User>
    suspend fun signup(name: String, email: String, password: String): Outcome<User>
    suspend fun forgotPassword(email: String): Outcome<Unit>
    /** Always succeeds locally, even offline: clears the session cookie and all user data on the device. */
    suspend fun logout()
}
