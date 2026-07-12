package es.personal.avisosairef.data.storage

import es.personal.avisosairef.data.parser.Publicacion
import kotlinx.serialization.Serializable

@Serializable
data class AppState(
    val formatVersion: Int = 1,
    val monitoredUrl: String = es.personal.avisosairef.Constants.DefaultPageUrl,
    val intervalMinutes: Long = es.personal.avisosairef.Constants.DefaultIntervalMinutes,
    val knownPublications: List<Publicacion> = emptyList(),
    val unseenPublications: List<Publicacion> = emptyList(),
    val recentPublications: List<Publicacion> = emptyList(),
    val eTag: String? = null,
    val lastModified: String? = null,
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastChangeAtMillis: Long? = null,
    val lastError: String? = null,
    val monitoringEnabled: Boolean = true,
    val lastResult: String = "Referencia pendiente"
)
