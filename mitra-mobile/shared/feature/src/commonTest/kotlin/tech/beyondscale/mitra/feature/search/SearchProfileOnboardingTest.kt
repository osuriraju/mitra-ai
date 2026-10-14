package tech.beyondscale.mitra.feature.search

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.feature.onboarding.CapturePreview
import tech.beyondscale.mitra.feature.onboarding.OnbArea
import tech.beyondscale.mitra.feature.onboarding.OnboardingAction
import tech.beyondscale.mitra.feature.onboarding.OnboardingViewModel
import tech.beyondscale.mitra.feature.onboarding.capturePreview
import tech.beyondscale.mitra.feature.profile.ProfileAction
import tech.beyondscale.mitra.feature.profile.ProfileForm
import tech.beyondscale.mitra.feature.profile.ProfileViewModel
import tech.beyondscale.mitra.testing.FakeAccountRepository
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FakeNotesRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SearchProfileOnboardingTest : ViewModelTest() {
    private val clock = FixedClock(Instant.parse("2026-10-14T06:00:00Z"))
    private val tasks = FakeTasksRepository(listOf(Task("t1", "Plan trip")))
    private fun auth(modules: Modules = Modules()) = FakeAuthRepository(SessionState.SignedIn(testUser(settings = Settings(modules = modules))))
    private fun search(modules: Modules = Modules()) = SearchViewModel(
        tasks, FakeNotesRepository(), FakeMoneyRepository(), FakeGoalsRepository(emptyList(), clock), FakeHabitsRepository(), auth(modules), clock,
    )

    @Test fun searchGroupsActionsThenRecords() = runTest {
        val vm = search()
        vm.onAction(SearchAction.Query("plan"))
        val s = vm.state.first { it.query == "plan" && it.groups.isNotEmpty() }
        assertEquals(listOf("Actions", "Tasks"), s.groups.map { it.title })
        assertEquals(SearchTarget.AskAi("plan"), s.groups[0].items[1].target)
        assertEquals(SearchTarget.OpenTask("t1"), s.groups[1].items.single().target)
    }

    @Test fun searchHidesAskMitraWithAiOffAndAddTaskCreatesAndOpens() = runTest {
        val vm = search(Modules(ai = false))
        vm.onAction(SearchAction.Query("Buy milk"))
        val s = vm.state.first { it.query == "Buy milk" && it.groups.isNotEmpty() }
        assertTrue(s.noRecords)
        val actions = s.groups.first().items
        assertEquals(1, actions.size)
        vm.onAction(SearchAction.Open(actions.single()))
        val opened = vm.navigation.first()
        assertIs<SearchTarget.OpenTask>(opened)
        assertTrue(tasks.taskFlow.value.any { it.title == "Buy milk" })
    }

    @Test fun idleSearchListsPlaces() = runTest {
        val s = search(Modules(money = false)).state.first { it.groups.isNotEmpty() }
        assertEquals(listOf("Go to"), s.groups.map { it.title })
    }

    @Test fun profileDeleteNeedsMatchingEmailAndReportsErrors() = runTest {
        val account = FakeAccountRepository()
        val vm = ProfileViewModel(auth(), account, tasks, FakeHabitsRepository(), FakeGoalsRepository(emptyList(), clock), FakeMoneyRepository(), clock)
        vm.state.first { it.email.isNotEmpty() }
        vm.onAction(ProfileAction.Delete("wrong@example.com", "pw"))
        assertTrue(account.calls.isEmpty())
        account.unitResult = Outcome.Err(AppError.Unauthenticated("BAD_PASSWORD", "Password is incorrect"))
        vm.onAction(ProfileAction.Delete("raju@example.com", "pw"))
        assertEquals(listOf("delete:pw"), account.calls)
        assertEquals(ProfileForm.Delete to "Password is incorrect", vm.state.first { it.error != null }.error)
        vm.onAction(ProfileAction.ChangePassword("old", "short"))
        assertEquals(listOf("delete:pw"), account.calls)
    }

    @Test fun capturePreviewPicksExpenseOnlyWithMoney() {
        assertIs<CapturePreview.Expense>(capturePreview("Spent ₹450 on dinner", true, emptyList(), emptyList(), "2026-10-14"))
        assertIs<CapturePreview.Task>(capturePreview("Call John tomorrow", true, emptyList(), emptyList(), "2026-10-14"))
        assertEquals(null, capturePreview(" ", true, emptyList(), emptyList(), "2026-10-14"))
    }

    @Test fun onboardingFinishSavesCaptureAndMarksOnboarded() = runTest {
        val account = FakeAccountRepository()
        val vm = OnboardingViewModel(account, tasks, FakeMoneyRepository(), clock)
        vm.onAction(OnboardingAction.Toggle(OnbArea.Money)) // off: the capture becomes a task
        vm.onAction(OnboardingAction.Toggle(OnbArea.Wellness))
        vm.onAction(OnboardingAction.Capture("Call John"))
        repeat(4) { vm.onAction(OnboardingAction.Next) }
        assertEquals(listOf("onboarded:${Modules(money = false, wellness = true, ai = true)}:true"), account.calls)
        assertTrue(tasks.taskFlow.value.any { it.title == "Call John" })
    }
}
