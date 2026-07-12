package es.personal.avisosairef

import android.content.Context
import es.personal.avisosairef.data.network.AirefHttpClient
import es.personal.avisosairef.data.repository.AirefRepository
import es.personal.avisosairef.data.storage.AirefStateStore

object ServiceLocator {
    @Volatile
    private var repository: AirefRepository? = null

    fun repository(context: Context): AirefRepository =
        repository ?: synchronized(this) {
            val appContext = context.applicationContext
            repository ?: AirefRepository(
                store = AirefStateStore(appContext),
                httpClient = AirefHttpClient(appContext)
            ).also { repository = it }
        }
}
