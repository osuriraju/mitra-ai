package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.User

interface AuthRepository {
    val session: StateFlow<SessionState>
    /**
     * Whether a user is cached on this device (signed in, or signed out server-side but with local data kept).
     * Readable without [restore] — the home-screen widget uses it in a process that never ran the app UI.
     */
    val hasLocalAccount: Flow<Boolean> get() = session.map { it is SessionState.SignedIn }
    /** The user cached on this device (null once signed out); like [hasLocalAccount], readable without [restore]. */
    val localUser: Flow<User?> get() = session.map { (it as? SessionState.SignedIn)?.user }
    /** On launch: show the cached user immediately (offline-first), then confirm with the server. */
    suspend fun restore()
    suspend fun refresh(): Outcome<User>
    suspend fun login(identifier: String, password: String): Outcome<User>
    suspend fun signup(name: String, email: String, password: String): Outcome<User>
    suspend fun forgotPassword(email: String): Outcome<Unit>
    /** Always succeeds locally, even offline: clears the session cookie and all user data on the device. */
    suspend fun logout()
}
