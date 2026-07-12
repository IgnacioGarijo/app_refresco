package es.personal.avisosairef.data.parser

import java.net.URI
import java.util.Locale

object UrlNormalizer {
    private val trackingParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "mc_cid", "mc_eid"
    )

    fun normalize(rawHref: String, baseUrl: String): String? = runCatching {
        val absolute = URI(baseUrl).resolve(rawHref.trim()).normalize()
        val scheme = absolute.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = absolute.host?.lowercase(Locale.ROOT) ?: return null
        val path = absolute.rawPath ?: "/"
        val query = normalizeQuery(absolute.rawQuery)
        URI(scheme, absolute.userInfo, host, absolute.port, path, query, null).toASCIIString()
    }.getOrNull()

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        val kept = rawQuery.split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", "")
                if (key.lowercase(Locale.ROOT) in trackingParams) null else part
            }
            .sorted()
        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }
}
