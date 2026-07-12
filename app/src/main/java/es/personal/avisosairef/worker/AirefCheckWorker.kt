package es.personal.avisosairef.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import es.personal.avisosairef.ServiceLocator
import es.personal.avisosairef.data.repository.CheckStatus
import es.personal.avisosairef.notifications.AirefNotificationManager
import es.personal.avisosairef.notifications.TelegramNotifier
import kotlinx.coroutines.flow.first

class AirefCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = ServiceLocator.repository(applicationContext)
        if (!repositorySnapshotEnabled(repository)) return Result.success()

        val outcomes = repository.checkDueMonitors()
        val state = repository.state.first()
        outcomes
            .filter { it.status == CheckStatus.Success && it.newPublications.isNotEmpty() && !it.firstReferenceCreated }
            .forEach {
                AirefNotificationManager(applicationContext).notifyNewPublications(it.monitorName ?: "Pagina vigilada", it.newPublications)
                if (state.telegramEnabled) {
                    TelegramNotifier().send(
                        state.telegramBotToken,
                        state.telegramChatId,
                        it.monitorName ?: "Pagina vigilada",
                        it.newPublications
                    )
                }
            }
        return when {
            outcomes.any { it.shouldRetry } -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun repositorySnapshotEnabled(repository: es.personal.avisosairef.data.repository.AirefRepository): Boolean =
        repository.state.first().monitoringEnabled
}
