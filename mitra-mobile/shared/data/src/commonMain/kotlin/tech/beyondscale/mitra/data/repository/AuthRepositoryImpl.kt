package tech.beyondscale.mitra.data.repository

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.map
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.UserEnvelope
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.SyncController

class AuthRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val cache: UserCache,
    private val prefs: PrefStore,
    private val localData: LocalData,
    private val cookies: PersistentCookiesStorage,
    private val sync: SyncController,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AuthRepository {
    private enum class Auth { Unknown, SignedOut, SignedIn }

    private val auth = MutableStateFlow(Auth.Unknown)

    override val session: StateFlow<SessionState> = combine(auth, cache.observe()) { a, user ->
        when {
            a == Auth.SignedIn && user != null -> SessionState.SignedIn(user)
            a == Auth.SignedOut -> SessionState.SignedOut
            else -> SessionState.Unknown
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Unknown)

    init {
        // Session gone server-side: back to Login, but keep local data + queued changes for the same user.
        scope.launch { api.unauthorized.collect { auth.value = Auth.SignedOut } }
    }

    override suspend fun restore() {
        val cached = withContext(io) {
            db.outboxQueries.resetInFlight() // the app may have been killed mid-send
            cache.current()
        }
        if (cached != null) {
            auth.value = Auth.SignedIn
            sync.requestSync()
            scope.launch { refresh() }
        } else if (refresh() is Outcome.Err) {
            auth.value = Auth.SignedOut
        }
    }

    override suspend fun refresh(): Outcome<User> = when (val r = api.call<UserEnvelope>(HttpMethod.Get, "auth/me")) {
        is Outcome.Err -> r
        is Outcome.Ok -> adopt(r.value.user)
    }

    override suspend fun login(identifier: String, password: String): Outcome<User> = signIn(
        api.call<UserEnvelope>(HttpMethod.Post, "auth/login", buildJsonObject {
            put("identifier", identifier); put("password", password); put("remember", true)
        }),
    )

    override suspend fun signup(name: String, email: String, password: String): Outcome<User> = signIn(
        api.call<UserEnvelope>(HttpMethod.Post, "auth/signup", buildJsonObject {
            put("name", name); put("email", email); put("password", password)
        }),
    )

    override suspend fun forgotPassword(email: String): Outcome<Unit> =
        api.send(HttpMethod.Post, "auth/forgot-password", buildJsonObject { put("email", email) }).map { }

    override suspend fun logout() {
        api.send(HttpMethod.Post, "auth/logout", JsonObject(emptyMap())) // best effort; offline is fine
        cookies.clear()
        withContext(io) {
            db.transaction {
                localData.clearUserData()
                prefs.remove(PrefKeys.LAST_USER_ID)
            }
        }
        auth.value = Auth.SignedOut
    }

    private suspend fun signIn(r: Outcome<UserEnvelope>): Outcome<User> = when (r) {
        is Outcome.Err -> r
        is Outcome.Ok -> adopt(r.value.user).also { sync.requestSync() }
    }

    /** Stores the server's user. A different account than the last one on this device wipes local data first. */
    private suspend fun adopt(dto: UserDto): Outcome<User> {
        withContext(io) {
            db.transaction {
                if (prefs.get(PrefKeys.LAST_USER_ID) != dto.id) localData.clearUserData()
                prefs.put(PrefKeys.LAST_USER_ID, dto.id)
                cache.put(dto)
            }
        }
        auth.value = Auth.SignedIn
        return Outcome.Ok(dto.toDomain(api.json))
    }
}
