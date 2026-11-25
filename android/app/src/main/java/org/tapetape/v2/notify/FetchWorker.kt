package org.tapetape.v2.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.tapetape.v2.Logx
import org.tapetape.v2.TapeTape
import java.util.concurrent.TimeUnit

class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TapeTape.awaitReady()
        val result = TapeTape.core.fetchAll()
        Logx.i("background fetch new=${result.newMessages} notifications=${result.notifications.size}")
        result.notifications.forEach { Notifier.post(applicationContext, it) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FetchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("fetch", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
