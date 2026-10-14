package tech.beyondscale.mitra.feature.di

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase
import tech.beyondscale.mitra.domain.usecase.LoginUseCase
import tech.beyondscale.mitra.domain.usecase.SignupUseCase
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.feature.ai.AiActionRunner
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

val featureModule = module {
    factoryOf(::AiActionRunner)
    factoryOf(::LoginUseCase)
    factoryOf(::SignupUseCase)
    factoryOf(::ForgotPasswordUseCase)
    viewModelOf(::LoginViewModel)
    viewModelOf(::SignupViewModel)
    viewModelOf(::ForgotPasswordViewModel)
    viewModelOf(::TodayViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::TasksViewModel)
    viewModelOf(::TaskEditorViewModel)
    viewModelOf(::QuickAddViewModel)
    viewModelOf(::MoneyViewModel)
    viewModelOf(::TxEditorViewModel)
    viewModelOf(::TransactionsViewModel)
    viewModelOf(::BudgetsViewModel)
    viewModelOf(::CategoriesViewModel)
    viewModelOf(::RecurringViewModel)
    viewModelOf(::SubscriptionsViewModel)
    viewModelOf(::HabitsViewModel)
    viewModelOf(::HabitEditorViewModel)
    viewModelOf(::GoalsViewModel)
    viewModelOf(::GoalEditorViewModel)
    viewModelOf(::GoalReviewViewModel)
    viewModelOf(::CheckinViewModel)
    viewModelOf(::WellnessTrendsViewModel)
    viewModelOf(::SleepDetailViewModel)
    // Like the web toggle, "Share with AI" also writes the account's shareWellness (the server shares when either is on).
    viewModel {
        val settings = get<SettingsRepository>()
        WellnessSettingsViewModel(get(), onShareAiChanged = { settings.setShareWellness(it) })
    }
    viewModelOf(::NotesViewModel)
    viewModelOf(::NotesSearchViewModel)
    viewModelOf(::JournalViewModel)
    viewModel { (id: String, startWriting: Boolean) -> NoteEditorViewModel(id, get(), get(), get(), get(), startWriting) }
    viewModelOf(::AiChatViewModel)
    viewModelOf(::AiSuggestionsViewModel)
    viewModelOf(::AiLogViewModel)
    viewModel { (id: String) -> HabitDetailViewModel(id, get(), get(), get()) }
    viewModel { (id: String) -> HabitHistoryViewModel(id, get(), get()) }
    viewModel { (id: String) -> GoalDetailViewModel(id, get(), get(), get(), get()) }
    viewModel { (id: String) -> TaskDetailViewModel(id, get(), get(), get(), get()) }
    viewModel { (taskId: String) -> FocusViewModel(taskId, get(), get(), get()) }
    viewModel { (id: String) -> ProjectDetailViewModel(id, get(), get(), get()) }
}
