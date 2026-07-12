package es.personal.avisosairef.data.repository

import es.personal.avisosairef.data.parser.Publicacion

data class CheckOutcome(
    val status: CheckStatus,
    val message: String,
    val monitorId: String? = null,
    val monitorName: String? = null,
    val newPublications: List<Publicacion> = emptyList(),
    val shouldRetry: Boolean = false,
    val firstReferenceCreated: Boolean = false
)

enum class CheckStatus {
    Success,
    NotModified,
    Error
}
