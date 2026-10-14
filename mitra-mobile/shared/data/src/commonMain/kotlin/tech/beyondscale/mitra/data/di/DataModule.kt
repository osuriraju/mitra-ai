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
import tech.beyondscale.mitra.core.ApplicationScope
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
import tech.beyondscale.mitra.data.repository.TasksRepositoryImpl
import tech.beyondscale.mitra.data.sync.ModuleRefresherImpl
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.PeriodicSyncController
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.ModuleRefresher
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.SettingsRepository
import tech.beyondscale.mitra.domain.repository.SyncController
import tech.beyondscale.mitra.domain.repository.TasksRepository

/** Process-wide scope for repositories and sync (outlives screens). */
class AppScope { val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }

/** Platform modules must also provide: SqlDriver, SecureStore, HttpClientEngine, SyncScheduler, PeriodicSync. */
fun dataModule(baseUrl: String): Module = module {
    single { AppScope() }
    single { ApplicationScope(get<AppScope>().scope) }
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
    // Eager: registers the task/project sync handlers, which must exist before the first drain.
    single<TasksRepository>(createdAtStart = true) { TasksRepositoryImpl(get(), get(), get(), get(), get(), get()) }
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }
    // Eager: it must see the first SignedIn and every drain from app start.
    single<ModuleRefresher>(createdAtStart = true) {
        val auth = get<AuthRepository>()
        val tasks = get<TasksRepository>()
        val engine = get<SyncEngine>()
        ModuleRefresherImpl(
            session = auth.session,
            drained = engine.drained,
            clock = get(),
            scope = get<AppScope>().scope,
            // Failures (offline, expired session) are ignored: the next trigger tries again.
            modules = mapOf<String, suspend () -> Unit>(
                "user" to { auth.refresh() },
                "tasks" to { tasks.refresh() },
            ),
        )
    }
    // Eager: schedules the 15-minute job whenever the outbox has rows (survives process death via WorkManager).
    single(createdAtStart = true) { PeriodicSyncController(get<SyncController>().pendingCount, get(), get<AppScope>().scope) }
}
