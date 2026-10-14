package tech.beyondscale.mitra.app

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import tech.beyondscale.mitra.data.remote.SecureStore
import tech.beyondscale.mitra.data.sync.PeriodicSync
import tech.beyondscale.mitra.data.sync.SyncScheduler
import tech.beyondscale.mitra.domain.repository.ActivityRepository
import tech.beyondscale.mitra.domain.repository.AiRepository
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.feature.ai.AiChatViewModel
import tech.beyondscale.mitra.feature.ai.AiLogViewModel
import tech.beyondscale.mitra.feature.ai.AiSuggestionsViewModel
import tech.beyondscale.mitra.feature.notes.JournalViewModel
import tech.beyondscale.mitra.feature.notes.NoteEditorViewModel
import tech.beyondscale.mitra.feature.notes.NotesSearchViewModel
import tech.beyondscale.mitra.feature.notes.NotesViewModel
import tech.beyondscale.mitra.feature.wellness.CheckinViewModel
import tech.beyondscale.mitra.feature.wellness.SleepDetailViewModel
import tech.beyondscale.mitra.feature.wellness.WellnessSettingsViewModel
import tech.beyondscale.mitra.feature.wellness.WellnessTrendsViewModel
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.ModuleRefresher
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.feature.auth.ForgotPasswordViewModel
import tech.beyondscale.mitra.feature.auth.LoginViewModel
import tech.beyondscale.mitra.feature.auth.SignupViewModel
import tech.beyondscale.mitra.feature.goals.GoalDetailViewModel
import tech.beyondscale.mitra.feature.goals.GoalEditorViewModel
import tech.beyondscale.mitra.feature.goals.GoalReviewViewModel
import tech.beyondscale.mitra.feature.goals.GoalsViewModel
import tech.beyondscale.mitra.feature.habits.HabitDetailViewModel
import tech.beyondscale.mitra.feature.habits.HabitEditorViewModel
import tech.beyondscale.mitra.feature.habits.HabitHistoryViewModel
import tech.beyondscale.mitra.feature.habits.HabitsViewModel
import tech.beyondscale.mitra.feature.money.BudgetsViewModel
import tech.beyondscale.mitra.feature.money.CategoriesViewModel
import tech.beyondscale.mitra.feature.money.MoneyViewModel
import tech.beyondscale.mitra.feature.money.RecurringViewModel
import tech.beyondscale.mitra.feature.money.SubscriptionsViewModel
import tech.beyondscale.mitra.feature.money.TransactionsViewModel
import tech.beyondscale.mitra.feature.money.TxEditorViewModel
import tech.beyondscale.mitra.feature.quickadd.QuickAddViewModel
import tech.beyondscale.mitra.feature.settings.SettingsViewModel
import tech.beyondscale.mitra.feature.tasks.FocusViewModel
import tech.beyondscale.mitra.feature.tasks.ProjectDetailViewModel
import tech.beyondscale.mitra.feature.tasks.TaskDetailViewModel
import tech.beyondscale.mitra.feature.tasks.TaskEditorViewModel
import tech.beyondscale.mitra.feature.tasks.TasksViewModel
import tech.beyondscale.mitra.feature.today.TodayViewModel
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.AfterTest
import kotlin.test.Test

/** The production graph resolves end to end with only the platform bindings swapped for test doubles. */
class AppModulesTest : ViewModelTest() {
    private val platform = module {
        single<SqlDriver> { testSqlDriver() }
        single<SecureStore> {
            object : SecureStore {
                private val values = mutableMapOf<String, String>()
                override fun get(key: String): String? = values[key]
                override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            }
        }
        single<HttpClientEngine> { MockEngine { respond("", HttpStatusCode.NotFound) } }
        single<SyncScheduler> { SyncScheduler {} }
        single<PeriodicSync> { PeriodicSync {} }
    }

    private fun koin(): Koin = startKoin { modules(appModules("http://test.local/api/") + platform) }.koin

    @AfterTest fun tearDown() { stopKoin() }

    @Test fun everyRepositoryAndViewModelResolves() {
        val koin = koin()
        koin.get<AuthRepository>()
        koin.get<SettingsRepository>()
        koin.get<PreferencesRepository>()
        koin.get<SyncController>()
        koin.get<ModuleRefresher>()
        koin.get<TasksRepository>()
        koin.get<MoneyRepository>()
        koin.get<LaunchRequests>()
        koin.get<RootViewModel>()
        koin.get<LoginViewModel>()
        koin.get<SignupViewModel>()
        koin.get<ForgotPasswordViewModel>()
        koin.get<TodayViewModel>()
        koin.get<SettingsViewModel>()
        koin.get<TasksViewModel>()
        koin.get<TaskEditorViewModel>()
        koin.get<QuickAddViewModel>()
        koin.get<TaskDetailViewModel> { parametersOf("t1") }
        koin.get<FocusViewModel> { parametersOf("") }
        koin.get<ProjectDetailViewModel> { parametersOf("p1") }
        koin.get<MoneyViewModel>()
        koin.get<TxEditorViewModel>()
        koin.get<TransactionsViewModel>()
        koin.get<BudgetsViewModel>()
        koin.get<CategoriesViewModel>()
        koin.get<RecurringViewModel>()
        koin.get<SubscriptionsViewModel>()
        koin.get<HabitsRepository>()
        koin.get<GoalsRepository>()
        koin.get<HabitsViewModel>()
        koin.get<HabitEditorViewModel>()
        koin.get<HabitDetailViewModel> { parametersOf("h1") }
        koin.get<HabitHistoryViewModel> { parametersOf("h1") }
        koin.get<GoalsViewModel>()
        koin.get<GoalEditorViewModel>()
        koin.get<GoalDetailViewModel> { parametersOf("g1") }
        koin.get<GoalReviewViewModel>()
        koin.get<WellnessRepository>()
        koin.get<NotesRepository>()
        koin.get<AiRepository>()
        koin.get<ActivityRepository>()
        koin.get<CheckinViewModel>()
        koin.get<WellnessTrendsViewModel>()
        koin.get<SleepDetailViewModel>()
        koin.get<WellnessSettingsViewModel>()
        koin.get<NotesViewModel>()
        koin.get<NotesSearchViewModel>()
        koin.get<JournalViewModel>()
        koin.get<NoteEditorViewModel> { parametersOf("n1", true) }
        koin.get<AiChatViewModel>()
        koin.get<AiSuggestionsViewModel>()
        koin.get<AiLogViewModel>()
    }
}
