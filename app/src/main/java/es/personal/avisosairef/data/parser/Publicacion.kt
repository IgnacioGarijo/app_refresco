package es.personal.avisosairef.data.parser

import kotlinx.serialization.Serializable

@Serializable
data class Publicacion(
    val title: String,
    val normalizedTitle: String,
    val url: String,
    val key: String,
    val type: String?,
    val detectedDate: String?
)
