package es.personal.avisosairef.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import es.personal.avisosairef.ServiceLocator
import es.personal.avisosairef.data.repository.CheckStatus
import es.personal.avisosairef.notifications.AirefNotificationManager
import kotlinx.coroutines.flow.first

class AirefCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = ServiceLocator.repository(applicationContext)
        if (!repositorySnapshotEnabled(repository)) return Result.success()

        val outcome = repository.checkNow()
        if (outcome.status == CheckStatus.Success && outcome.newPublications.isNotEmpty() && !outcome.firstReferenceCreated) {
            AirefNotificationManager(applicationContext).notifyNewPublications(outcome.newPublications)
        }
        return when {
            outcome.shouldRetry -> Result.retry()
            outcome.status == CheckStatus.Error -> Result.success()
            else -> Result.success()
        }
    }

    private suspend fun repositorySnapshotEnabled(repository: es.personal.avisosairef.data.repository.AirefRepository): Boolean =
        repository.state.first().monitoringEnabled
}
