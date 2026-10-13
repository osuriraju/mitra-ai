package tech.beyondscale.mitra.data.repository

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.data.sync.withPendingSettings
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
import kotlinx.coroutines.withTimeoutOrNull
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
        // UNDISPATCHED so the collector is subscribed before anything can emit.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.unauthorized.collect { if (refreshing.value == 0) auth.value = Auth.SignedOut }
        }
    }

    /**
     * Session epoch: bumped (under [lock]) by logout, and checked under the same lock before an adopt writes.
     * A request that started before a logout therefore can't resurrect the user. Refresh handles its own 401
     * (only if the epoch is unchanged); the shared `unauthorized` broadcast is ignored while a refresh is in
     * flight so its late delivery can't undo a newer sign-in.
     */
    private val lock = Mutex()
    private var epoch = 0L
    private val refreshing = MutableStateFlow(0)
    private var refreshJob: Job? = null

    override suspend fun restore() {
        val cached = withContext(io) {
            db.outboxQueries.resetInFlight() // the app may have been killed mid-send
            cache.current()
        }
        if (cached != null) {
            auth.value = Auth.SignedIn
            sync.requestSync()
            refreshJob = scope.launch { refresh() }
        } else if (refresh() is Outcome.Err) {
            auth.value = Auth.SignedOut
        }
    }

    override suspend fun refresh(): Outcome<User> {
        val started = epoch
        refreshing.update { it + 1 }
        try {
            return when (val r = api.call<UserEnvelope>(HttpMethod.Get, "auth/me")) {
                is Outcome.Err -> {
                    val e = r.error
                    if (e is AppError.Unauthenticated && e.code == "UNAUTHENTICATED" && started == epoch) auth.value = Auth.SignedOut
                    r
                }
                is Outcome.Ok -> adopt(r.value.user, started)
            }
        } finally {
            refreshing.update { it - 1 }
        }
    }

    override suspend fun login(identifier: String, password: String): Outcome<User> = epoch.let { started -> signIn(
        started, api.call<UserEnvelope>(HttpMethod.Post, "auth/login", buildJsonObject {
            put("identifier", identifier); put("password", password); put("remember", true)
        }),
    ) }

    override suspend fun signup(name: String, email: String, password: String): Outcome<User> = epoch.let { started -> signIn(
        started, api.call<UserEnvelope>(HttpMethod.Post, "auth/signup", buildJsonObject {
            put("name", name); put("email", email); put("password", password)
        }),
    ) }

    override suspend fun forgotPassword(email: String): Outcome<Unit> =
        api.send(HttpMethod.Post, "auth/forgot-password", buildJsonObject { put("email", email) }).map { }

    override suspend fun logout() {
        lock.withLock { epoch++ } // from here on, in-flight refresh/login results are dropped
        refreshJob?.cancel()
        // Best effort, and bounded: offline or a captive network must not hold sign-out for the full request timeout.
        withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { api.send(HttpMethod.Post, "auth/logout", JsonObject(emptyMap())) }
        lock.withLock {
            epoch++
            cookies.clear()
            withContext(io) {
                db.transaction {
                    localData.clearUserData()
                    prefs.remove(PrefKeys.LAST_USER_ID)
                }
            }
            auth.value = Auth.SignedOut
        }
    }

    private suspend fun signIn(started: Long, r: Outcome<UserEnvelope>): Outcome<User> = when (r) {
        is Outcome.Err -> r
        is Outcome.Ok -> adopt(r.value.user, started).also { if (it is Outcome.Ok) sync.requestSync() }
    }

    /** Stores the server's user. A different account than the last one on this device wipes local data first. */
    private suspend fun adopt(dto: UserDto, started: Long): Outcome<User> = lock.withLock {
        if (started != epoch) return@withLock Outcome.Err(AppError.Unauthenticated("SESSION_ENDED", "Signed out"))
        val stored = withContext(io) {
            db.transactionWithResult {
                if (prefs.get(PrefKeys.LAST_USER_ID) != dto.id) localData.clearUserData()
                prefs.put(PrefKeys.LAST_USER_ID, dto.id)
                // Queued settings changes stay visible on top of the server copy until they are acknowledged.
                dto.withPendingSettings(db, api.json).also { cache.put(it) }
            }
        }
        auth.value = Auth.SignedIn
        Outcome.Ok(stored.toDomain(api.json))
    }

    private companion object { const val LOGOUT_TIMEOUT_MS = 4_000L }
}
