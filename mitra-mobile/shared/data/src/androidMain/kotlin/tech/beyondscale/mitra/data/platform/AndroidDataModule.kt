package tech.beyondscale.mitra.data.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.SecureStore

/** Android bindings for dataModule(); the app adds its SyncScheduler. */
fun androidDataModule(context: Context): Module = module {
    single<SqlDriver> { AndroidSqliteDriver(MitraDb.Schema, context, "mitra.db") }
    single<SecureStore> { KeystoreSecureStore(context) }
    single<HttpClientEngine> { OkHttp.create() }
}
