package com.missingtable.scorer.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.missingtable.scorer.MtApp
import java.util.concurrent.TimeUnit

/**
 * WorkManager fallback so a match scored offline still syncs when the app is
 * backgrounded on reconnect. The in-process engine handles the common case;
 * this exists for process death / doze.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as MtApp).container.syncEngine
        return when (engine.drain()) {
            SyncEngine.DrainResult.EMPTY -> Result.success()
            // PAUSED needs user input — retrying won't help until they act.
            SyncEngine.DrainResult.PAUSED -> Result.success()
            SyncEngine.DrainResult.TRANSIENT_FAILURE -> Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("mt-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}
