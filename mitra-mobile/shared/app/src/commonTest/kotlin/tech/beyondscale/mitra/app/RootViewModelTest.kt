package tech.beyondscale.mitra.app

import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakePreferencesRepository
import tech.beyondscale.mitra.testing.FakeSyncController
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RootViewModelTest : ViewModelTest() {
    @Test fun restoresSessionOnStartAndFollowsTheme() {
        val auth = FakeAuthRepository(); val prefs = FakePreferencesRepository(); val sync = FakeSyncController()
        val vm = RootViewModel(auth, prefs, sync)
        assertEquals(listOf("restore"), auth.calls)
        prefs.themeFlow.value = ThemeMode.Light
        assertEquals(ThemeMode.Light, vm.state.value.theme)
        vm.onForeground()
        assertEquals(1, sync.requests)
    }
}
