package es.personal.avisosairef

import android.app.Application
import es.personal.avisosairef.notifications.AirefNotificationManager
import es.personal.avisosairef.worker.AirefWorkScheduler

class AvisosAirefApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AirefNotificationManager(this).createChannel()
        AirefWorkScheduler.schedulePeriodic(this)
    }
}
