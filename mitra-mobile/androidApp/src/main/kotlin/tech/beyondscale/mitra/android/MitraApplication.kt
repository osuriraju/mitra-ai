package tech.beyondscale.mitra.android

import android.app.Application
import android.util.Log
import android.net.ConnectivityManager
import android.net.Network
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import tech.beyondscale.mitra.android.widget.WidgetUpdater
import tech.beyondscale.mitra.app.appModules
import tech.beyondscale.mitra.core.MitraLog
import tech.beyondscale.mitra.data.di.AppScope
import tech.beyondscale.mitra.data.platform.androidDataModule
import tech.beyondscale.mitra.data.sync.PeriodicSync
import tech.beyondscale.mitra.data.sync.SyncScheduler
import tech.beyondscale.mitra.domain.repository.SyncController
import tech.beyondscale.mitra.feature.tasks.FocusKeepAlive

class MitraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MitraLog.sink = { tag, message, error -> Log.w(tag, message, error) }
        checkApiUrl(BuildConfig.API_URL, allowCleartext = BuildConfig.DEBUG)
        startKoin {
            androidContext(this@MitraApplication)
            modules(
                listOf(
                    androidDataModule(this@MitraApplication),
                    module {
                        single<SyncScheduler> { WorkManagerSyncScheduler(this@MitraApplication) }
                        single<PeriodicSync> { WorkManagerPeriodicSync(this@MitraApplication) }
                        single<FocusKeepAlive> { AndroidFocusKeepAlive(this@MitraApplication) }
                    },
                ) +
                    appModules(BuildConfig.API_URL),
            )
        }
        // Home-screen widgets: redraw on data / session / theme changes and at local midnight.
        WidgetUpdater(this, get(), get(), get(), get(), get(), get<AppScope>().scope).start()
        // Network back → flush queued changes right away.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { get<SyncController>().requestSync() }
        })
    }
}
