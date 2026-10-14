package tech.beyondscale.mitra.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tech.beyondscale.mitra.data.sync.PeriodicSync
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncScheduler

/** Drains the outbox once a network is available; WorkManager retries with exponential backoff. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val engine: SyncEngine by inject()

    // WorkManager's own retry/backoff reschedules this job; asking the engine to schedule too would chain another worker.
    override suspend fun doWork(): Result = when (engine.drain(scheduleRetryOnFailure = false)) {
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

/** 15-minute safety-net drain while changes are queued (spec §6); enabled by PeriodicSyncController. */
class WorkManagerPeriodicSync(private val context: Context) : PeriodicSync {
    override fun setEnabled(enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object { const val NAME = "mitra-sync-periodic" }
}
