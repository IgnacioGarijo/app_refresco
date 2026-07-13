package es.personal.avisosairef.data.storage

import es.personal.avisosairef.Constants
import es.personal.avisosairef.data.parser.Publicacion
import kotlinx.serialization.Serializable

@Serializable
data class AppState(
    val formatVersion: Int = 2,
    val monitors: List<WebMonitor> = listOf(WebMonitor.default()),
    val selectedMonitorId: String = monitors.firstOrNull()?.id ?: Constants.DefaultMonitorId,
    val monitoringEnabled: Boolean = true,
    val telegramEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val defaultIntervalMinutes: Long = Constants.DefaultIntervalMinutes
) {
    val selectedMonitor: WebMonitor
        get() = monitors.firstOrNull { it.id == selectedMonitorId } ?: monitors.firstOrNull() ?: WebMonitor.default()
}

@Serializable
data class WebMonitor(
    val id: String,
    val name: String,
    val folder: String = "Personal",
    val monitoredUrl: String,
    val intervalMinutes: Long = Constants.DefaultIntervalMinutes,
    val enabled: Boolean = true,
    val cssSelector: String = "",
    val includeKeywords: String = "",
    val knownPublications: List<Publicacion> = emptyList(),
    val unseenPublications: List<Publicacion> = emptyList(),
    val recentPublications: List<Publicacion> = emptyList(),
    val eTag: String? = null,
    val lastModified: String? = null,
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastChangeAtMillis: Long? = null,
    val lastError: String? = null,
    val lastResult: String = "Referencia pendiente"
) {
    companion object {
        fun default(): WebMonitor = WebMonitor(
            id = Constants.DefaultMonitorId,
            name = "Pagina de ejemplo",
            folder = "General",
            monitoredUrl = Constants.DefaultPageUrl
        )
    }
}
