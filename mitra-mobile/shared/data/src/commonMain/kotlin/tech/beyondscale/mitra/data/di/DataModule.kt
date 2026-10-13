package tech.beyondscale.mitra.data.di

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.SystemClock
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.repository.AuthRepositoryImpl
import tech.beyondscale.mitra.data.repository.PreferencesRepositoryImpl
import tech.beyondscale.mitra.data.repository.SettingsRepositoryImpl
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController

/** Process-wide scope for repositories and sync (outlives screens). */
class AppScope { val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }

/** Platform modules must also provide: SqlDriver, SecureStore, HttpClientEngine, SyncScheduler. */
fun dataModule(baseUrl: String): Module = module {
    single { AppScope() }
    single<AppClock> { SystemClock }
    single { MitraDb(get<SqlDriver>()) }
    single { PersistentCookiesStorage(get(), get()) }
    single { ApiClient(get<HttpClientEngine>(), baseUrl, get<PersistentCookiesStorage>()) }
    single<Json> { get<ApiClient>().json }
    single { UserCache(get(), get(), Dispatchers.IO) }
    single { PrefStore(get(), Dispatchers.IO) }
    single { LocalData(get()) }
    single { OutboxWriter(get(), get(), get()) }
    single { SyncEngine(get(), get(), get(), get<AppScope>().scope) }
    single<SyncController> { get<SyncEngine>() }
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get<AppScope>().scope) }
    // Eager: it registers the user-settings sync handler, which must exist before the first drain.
    single<SettingsRepository>(createdAtStart = true) { SettingsRepositoryImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }
}
