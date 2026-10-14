package tech.beyondscale.mitra.feature.di

import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tech.beyondscale.mitra.domain.usecase.ForgotPasswordUseCase
import tech.beyondscale.mitra.domain.usecase.LoginUseCase
import tech.beyondscale.mitra.domain.usecase.SignupUseCase
import tech.beyondscale.mitra.feature.auth.ForgotPasswordViewModel
import tech.beyondscale.mitra.feature.auth.LoginViewModel
import tech.beyondscale.mitra.feature.auth.SignupViewModel
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
    viewModel { (id: String) -> TaskDetailViewModel(id, get(), get(), get(), get()) }
    viewModel { (taskId: String) -> FocusViewModel(taskId, get(), get(), get()) }
    viewModel { (id: String) -> ProjectDetailViewModel(id, get(), get(), get()) }
}
