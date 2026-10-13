package tech.beyondscale.mitra.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    private fun vm() = SettingsViewModel(auth, settings, prefs, sync)

    @Test fun exposesAccountThemeAndPending() = runTest {
        sync.pending.value = 3
        val s = vm().state.first { it.email.isNotEmpty() }
        assertEquals("RO", s.initials)
        assertEquals(Modules(wellness = false), s.modules)
        assertEquals(3L, s.pending)
    }

    @Test fun togglingAModuleKeepsTheOthers() {
        vm().onAction(SettingsAction.SetModule(ModuleKey.Money, false))
        assertEquals(listOf(Modules(money = false, wellness = false, ai = true)), settings.updates)
    }

    @Test fun setThemeAndSignOut() {
        val vm = vm()
        vm.onAction(SettingsAction.SetTheme(ThemeMode.Light))
        assertEquals(ThemeMode.Light, prefs.themeFlow.value)
        vm.onAction(SettingsAction.SignOut)
        assertEquals(listOf("logout"), auth.calls)
    }
}
