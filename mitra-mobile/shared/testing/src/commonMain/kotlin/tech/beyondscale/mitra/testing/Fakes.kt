package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.ModuleKey
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController

fun testUser(name: String = "Raju Osuri", settings: Settings = Settings(), id: String = "u1") = User(
    id = id, name = name, email = "raju@example.com", username = null, phone = null,
    settings = settings, onboardedAt = null, memberSince = "2026-09-01T00:00:00.000Z",
)

class FakeAuthRepository(initial: SessionState = SessionState.SignedOut) : AuthRepository {
    val sessionFlow = MutableStateFlow(initial)
    override val session: StateFlow<SessionState> = sessionFlow.asStateFlow()
    var result: Outcome<User> = Outcome.Ok(testUser())
    var forgotResult: Outcome<Unit> = Outcome.Ok(Unit)
    val calls = mutableListOf<String>()

    override suspend fun restore() { calls += "restore" }
    override suspend fun refresh(): Outcome<User> { calls += "refresh"; return result }
    override suspend fun login(identifier: String, password: String): Outcome<User> {
        calls += "login:$identifier"
        return result.also { if (it is Outcome.Ok) sessionFlow.value = SessionState.SignedIn(it.value) }
    }
    override suspend fun signup(name: String, email: String, password: String): Outcome<User> {
        calls += "signup:$name:$email"
        return result.also { if (it is Outcome.Ok) sessionFlow.value = SessionState.SignedIn(it.value) }
    }
    override suspend fun forgotPassword(email: String): Outcome<Unit> { calls += "forgot:$email"; return forgotResult }
    override suspend fun logout() { calls += "logout"; sessionFlow.value = SessionState.SignedOut }
}

class FakeSettingsRepository : SettingsRepository {
    val updates = mutableListOf<Pair<ModuleKey, Boolean>>()
    override suspend fun setModule(key: ModuleKey, enabled: Boolean) { updates += key to enabled }
    val shareWellness = mutableListOf<Boolean>()
    override suspend fun setShareWellness(enabled: Boolean) { shareWellness += enabled }
}

class FakePreferencesRepository : PreferencesRepository {
    val themeFlow = MutableStateFlow(ThemeMode.Dark)
    override val theme = themeFlow
    override suspend fun setTheme(mode: ThemeMode) { themeFlow.value = mode }
}

class FakeSyncController : SyncController {
    val statusFlow = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = statusFlow
    val pending = MutableStateFlow(0L)
    override val pendingCount = pending
    val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages = messageFlow
    var requests = 0
    override fun requestSync() { requests++ }
}
