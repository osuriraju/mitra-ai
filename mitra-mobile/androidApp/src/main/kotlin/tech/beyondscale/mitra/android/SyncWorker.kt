package tech.beyondscale.mitra.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncScheduler

/** Drains the outbox once a network is available; WorkManager retries with exponential backoff. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val engine: SyncEngine by inject()

    override suspend fun doWork(): Result = when (engine.drain()) {
        SyncEngine.Result.Drained, SyncEngine.Result.SignedOut -> Result.success()
        SyncEngine.Result.Offline, SyncEngine.Result.Retry -> Result.retry()
    }
}

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    override fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("mitra-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
