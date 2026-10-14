package tech.beyondscale.mitra.app

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tech.beyondscale.mitra.data.di.dataModule
import tech.beyondscale.mitra.feature.di.featureModule

/** Everything except platform bindings (SqlDriver, SecureStore, HttpClientEngine, SyncScheduler). */
fun appModules(baseUrl: String): List<Module> = listOf(
    dataModule(baseUrl),
    featureModule,
    module {
        viewModelOf(::RootViewModel)
        single { LaunchRequests() }
    },
)
