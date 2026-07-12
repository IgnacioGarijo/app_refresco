package es.personal.avisosairef

import android.app.Application
import es.personal.avisosairef.notifications.AirefNotificationManager
import es.personal.avisosairef.worker.AirefWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AvisosAirefApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AirefNotificationManager(this).createChannel()
        CoroutineScope(Dispatchers.Default).launch {
            val state = ServiceLocator.repository(this@AvisosAirefApp).state.first()
            if (state.monitoringEnabled) {
                AirefWorkScheduler.schedulePeriodic(this@AvisosAirefApp, state.intervalMinutes)
            }
        }
    }
}
