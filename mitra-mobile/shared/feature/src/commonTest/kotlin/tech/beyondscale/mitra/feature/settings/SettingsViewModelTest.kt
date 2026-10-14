package tech.beyondscale.mitra.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AuthRepository
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.ModuleKey
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakePreferencesRepository
import tech.beyondscale.mitra.testing.FakeSettingsRepository
import tech.beyondscale.mitra.testing.FakeSyncController
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest : ViewModelTest() {
    private val auth = FakeAuthRepository(SessionState.SignedIn(testUser(settings = Settings(modules = Modules(wellness = false)))))
    private val settings = FakeSettingsRepository()
    private val prefs = FakePreferencesRepository()
    private val sync = FakeSyncController()
    private val editor = tech.beyondscale.mitra.testing.FakeSettingsEditor()
    private fun vm() = SettingsViewModel(auth, settings, prefs, sync, editor)

    @Test fun exposesAccountThemeAndPending() = runTest {
        sync.pending.value = 3
        val s = vm().state.first { it.email.isNotEmpty() }
        assertEquals("RO", s.initials)
        assertEquals(Modules(wellness = false), s.modules)
        assertEquals(3L, s.pending)
    }

    @Test fun togglesAreSentPerModule() {
        val vm = vm()
        vm.onAction(SettingsAction.SetModule(ModuleKey.Money, false))
        vm.onAction(SettingsAction.SetModule(ModuleKey.Ai, false))
        assertEquals(listOf(ModuleKey.Money to false, ModuleKey.Ai to false), settings.updates)
    }

    @Test fun setThemeAndSignOut() {
        val vm = vm()
        vm.onAction(SettingsAction.SetTheme(ThemeMode.Light))
        assertEquals(ThemeMode.Light, prefs.themeFlow.value)
        vm.onAction(SettingsAction.SignOut)
        assertEquals(listOf("logout"), auth.calls)
    }

    @Test fun signingOutResetsWhenLogoutThrows() = runTest {
        val failing = object : AuthRepository {
            override val session: StateFlow<SessionState> = MutableStateFlow(SessionState.SignedIn(testUser()))
            override suspend fun restore() {}
            override suspend fun refresh(): Outcome<User> = error("unused")
            override suspend fun login(identifier: String, password: String): Outcome<User> = error("unused")
            override suspend fun signup(name: String, email: String, password: String): Outcome<User> = error("unused")
            override suspend fun forgotPassword(email: String): Outcome<Unit> = error("unused")
            override suspend fun logout() { throw IllegalStateException("boom") }
        }
        val vm = SettingsViewModel(failing, settings, prefs, sync, editor)
        backgroundScope.launch { vm.state.collect {} }
        runCatching { vm.onAction(SettingsAction.SignOut) }
        assertEquals(false, vm.state.value.signingOut)
    }

    @Test fun settingChangesGoToTheEditor() {
        val vm = vm()
        vm.onAction(SettingsAction.Change(tech.beyondscale.mitra.domain.repository.SettingChange.Digest(false)))
        vm.onAction(SettingsAction.Change(tech.beyondscale.mitra.domain.repository.SettingChange.FocusMinutes(45)))
        assertEquals(
            listOf(tech.beyondscale.mitra.domain.repository.SettingChange.Digest(false), tech.beyondscale.mitra.domain.repository.SettingChange.FocusMinutes(45)),
            editor.changes,
        )
    }
}
