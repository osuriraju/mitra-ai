package tech.beyondscale.mitra.app

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import tech.beyondscale.mitra.data.remote.SecureStore
import tech.beyondscale.mitra.data.sync.SyncScheduler
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController
import tech.beyondscale.mitra.feature.auth.ForgotPasswordViewModel
import tech.beyondscale.mitra.feature.auth.LoginViewModel
import tech.beyondscale.mitra.feature.auth.SignupViewModel
import tech.beyondscale.mitra.feature.settings.SettingsViewModel
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
    }

    private fun koin(): Koin = startKoin { modules(appModules("http://test.local/api/") + platform) }.koin

    @AfterTest fun tearDown() { stopKoin() }

    @Test fun everyRepositoryAndViewModelResolves() {
        val koin = koin()
        koin.get<AuthRepository>()
        koin.get<SettingsRepository>()
        koin.get<PreferencesRepository>()
        koin.get<SyncController>()
        koin.get<RootViewModel>()
        koin.get<LoginViewModel>()
        koin.get<SignupViewModel>()
        koin.get<ForgotPasswordViewModel>()
        koin.get<TodayViewModel>()
        koin.get<SettingsViewModel>()
    }
}
