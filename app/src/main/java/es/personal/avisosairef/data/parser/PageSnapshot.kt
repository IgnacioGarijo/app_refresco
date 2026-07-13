package es.personal.avisosairef.data.parser

import kotlinx.serialization.Serializable

@Serializable
data class PageSnapshot(
    val textHash: String,
    val domHash: String,
    val linksHash: String,
    val metadataHash: String,
    val imagesHash: String,
    val combinedHash: String,
    val visibleTextLength: Int,
    val linkCount: Int,
    val imageCount: Int,
    val metadataCount: Int,
    val dynamicHints: List<String> = emptyList()
)
