package tech.beyondscale.mitra.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.notify.NotificationPrefs
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakePreferencesRepository
import tech.beyondscale.mitra.testing.FakeSettingsRepository
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NotificationSettingsViewModelTest : ViewModelTest() {
    private val auth = FakeAuthRepository(SessionState.SignedIn(testUser(settings = Settings(quietHours = false, modules = Modules(money = false)))))
    private val settings = FakeSettingsRepository()
    private val prefs = FakePreferencesRepository()
    private fun vm() = NotificationSettingsViewModel(auth, settings, prefs)

    @Test fun exposesAccountQuietHoursAndMoneyModule() = runTest {
        val s = vm().state.first { !it.moneyOn }
        assertFalse(s.quietHours)
        assertEquals(NotificationPrefs(), s.prefs)
    }

    @Test fun switchesKeepEachOther() {
        val vm = vm()
        vm.onAction(NotificationSettingsAction.SetTasks(false))
        vm.onAction(NotificationSettingsAction.SetBills(false))
        assertEquals(NotificationPrefs(tasks = false, habits = true, bills = false), prefs.notifyFlow.value)
        vm.onAction(NotificationSettingsAction.SetQuietHours(true))
        assertEquals(listOf(true), settings.quietHours)
    }
}
