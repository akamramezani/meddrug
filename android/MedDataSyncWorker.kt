package com.example.medconsults.data // TODO: change to your app's package

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background job that keeps the on-device copy of the Medication Matrix data fresh. */
class MedDataSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (MedDataRepository(applicationContext).sync()) {
            is SyncResult.Failed -> Result.retry() // offline or server hiccup: WorkManager backs off and retries
            else -> Result.success()
        }

    companion object {
        private const val UNIQUE_NAME = "med-data-sync"

        /** Call once, e.g. from Application.onCreate(). Checks about once a day whenever a network is available. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MedDataSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
