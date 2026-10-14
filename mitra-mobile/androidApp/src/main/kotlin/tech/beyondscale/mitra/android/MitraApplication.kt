package tech.beyondscale.mitra.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import tech.beyondscale.mitra.app.appModules
import tech.beyondscale.mitra.data.platform.androidDataModule
import tech.beyondscale.mitra.data.sync.PeriodicSync
import tech.beyondscale.mitra.data.sync.SyncScheduler
import tech.beyondscale.mitra.domain.repository.SyncController

class MitraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.API_URL.isNotBlank()) { "API URL missing: pass -Pmitra.releaseApiUrl=https://…/api/ for release builds" }
        startKoin {
            androidContext(this@MitraApplication)
            modules(
                listOf(
                    androidDataModule(this@MitraApplication),
                    module {
                        single<SyncScheduler> { WorkManagerSyncScheduler(this@MitraApplication) }
                        single<PeriodicSync> { WorkManagerPeriodicSync(this@MitraApplication) }
                    },
                ) +
                    appModules(BuildConfig.API_URL),
            )
        }
        // Network back → flush queued changes right away.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { get<SyncController>().requestSync() }
        })
    }
}
