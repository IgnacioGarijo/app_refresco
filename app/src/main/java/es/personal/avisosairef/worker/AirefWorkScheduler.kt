package es.personal.avisosairef.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import es.personal.avisosairef.Constants
import java.time.Duration
import java.util.concurrent.TimeUnit

object AirefWorkScheduler {
    fun schedulePeriodic(context: Context, intervalMinutes: Long = es.personal.avisosairef.Constants.DefaultIntervalMinutes) {
        val safeInterval = intervalMinutes.coerceAtLeast(15)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<AirefCheckWorker>(safeInterval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(10))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            Constants.UniquePeriodicWork,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(Constants.UniquePeriodicWork)
    }

    fun enqueueOneTime(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<AirefCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
