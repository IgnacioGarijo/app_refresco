package es.personal.avisosairef.data.repository

import es.personal.avisosairef.Constants
import es.personal.avisosairef.data.network.AirefFetcher
import es.personal.avisosairef.data.network.AirefHttpClient
import es.personal.avisosairef.data.network.FetchError
import es.personal.avisosairef.data.network.FetchResult
import es.personal.avisosairef.data.parser.AirefPublicationsParser
import es.personal.avisosairef.data.parser.ParserResult
import es.personal.avisosairef.data.storage.AppState
import es.personal.avisosairef.data.storage.StateStore
import kotlinx.coroutines.flow.Flow

class AirefRepository(
    private val store: StateStore,
    private val httpClient: AirefFetcher = AirefHttpClient(),
    private val parser: AirefPublicationsParser = AirefPublicationsParser(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    val state: Flow<AppState> = store.state

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        store.update { it.copy(monitoringEnabled = enabled) }
    }

    suspend fun updateSettings(monitoredUrl: String, intervalMinutes: Long, sectionFilterEnabled: Boolean) {
        val cleanUrl = monitoredUrl.trim()
        require(cleanUrl.startsWith("https://")) { "La URL debe empezar por https://" }
        store.update {
            if (it.monitoredUrl == cleanUrl) {
                it.copy(intervalMinutes = intervalMinutes, sectionFilterEnabled = sectionFilterEnabled)
            } else {
                it.copy(
                    monitoredUrl = cleanUrl,
                    intervalMinutes = intervalMinutes,
                    sectionFilterEnabled = sectionFilterEnabled,
                    knownPublications = emptyList(),
                    unseenPublications = emptyList(),
                    recentPublications = emptyList(),
                    eTag = null,
                    lastModified = null,
                    lastSuccessAtMillis = null,
                    lastChangeAtMillis = null,
                    lastError = null,
                    lastResult = "Referencia pendiente: URL actualizada."
                )
            }
        }
    }

    suspend fun resetReference() {
        store.resetReference()
    }

    suspend fun markUnseenAsViewed() {
        store.update { it.copy(unseenPublications = emptyList()) }
    }

    suspend fun checkNow(): CheckOutcome {
        val before = store.current()
        val now = clock()
        store.update { it.copy(lastAttemptAtMillis = now) }

        return when (val fetch = httpClient.fetch(before.monitoredUrl, before.eTag, before.lastModified)) {
            is FetchResult.NotModified -> {
                store.update {
                    it.copy(
                        eTag = fetch.eTag,
                        lastModified = fetch.lastModified,
                        lastSuccessAtMillis = now,
                        lastError = null,
                        lastResult = "Sin cambios: el servidor respondio 304."
                    )
                }
                CheckOutcome(CheckStatus.NotModified, "Sin cambios")
            }

            is FetchResult.Failure -> {
                val message = fetch.error.toUserMessage()
                store.update { it.copy(lastError = message, lastResult = message) }
                CheckOutcome(CheckStatus.Error, message, shouldRetry = fetch.error is FetchError.Network || fetch.error is FetchError.Http)
            }

            is FetchResult.Success -> handleHtml(before, fetch, now)
        }
    }

    private suspend fun handleHtml(before: AppState, fetch: FetchResult.Success, now: Long): CheckOutcome {
        val targetSection = if (before.sectionFilterEnabled) Constants.TargetSection else null
        return when (val parsed = parser.parse(fetch.html, before.monitoredUrl, previousKnownCount = before.knownPublications.size, targetTitle = targetSection)) {
            is ParserResult.Failure -> {
                val message = "Error de parsing: ${parsed.message}"
                store.update {
                    it.copy(
                        eTag = fetch.eTag,
                        lastModified = fetch.lastModified,
                        lastError = message,
                        lastResult = message
                    )
                }
                CheckOutcome(CheckStatus.Error, message, shouldRetry = false)
            }

            is ParserResult.Success -> {
                val current = parsed.publications
                if (before.knownPublications.isEmpty()) {
                    val message = "Referencia inicial creada: ${current.size} enlaces encontrados."
                    store.update {
                        it.copy(
                            knownPublications = current,
                            eTag = fetch.eTag,
                            lastModified = fetch.lastModified,
                            lastSuccessAtMillis = now,
                            lastError = null,
                            lastResult = message
                        )
                    }
                    return CheckOutcome(CheckStatus.Success, message, firstReferenceCreated = true)
                }

                val knownKeys = before.knownPublications.map { it.key }.toSet()
                val newItems = current.filterNot { it.key in knownKeys }
                val recent = (newItems + before.recentPublications).distinctBy { it.key }.take(20)
                val message = if (newItems.isEmpty()) "Comprobacion correcta: sin novedades." else "Novedades detectadas: ${newItems.size}."
                store.update {
                    it.copy(
                        knownPublications = current,
                        unseenPublications = (newItems + it.unseenPublications).distinctBy { pub -> pub.key },
                        recentPublications = recent,
                        eTag = fetch.eTag,
                        lastModified = fetch.lastModified,
                        lastSuccessAtMillis = now,
                        lastChangeAtMillis = if (newItems.isNotEmpty()) now else it.lastChangeAtMillis,
                        lastError = null,
                        lastResult = message
                    )
                }
                CheckOutcome(CheckStatus.Success, if (newItems.isEmpty()) "Sin novedades" else "${newItems.size} novedades", newItems)
            }
        }
    }

    private fun FetchError.toUserMessage(): String = when (this) {
        is FetchError.Network -> "Error de red: $message"
        is FetchError.Tls -> "Error TLS/certificado: $message"
        is FetchError.Http -> "Respuesta HTTP no valida: $code"
        is FetchError.TooLarge -> "Respuesta demasiado grande; limite ${maxBytes / 1024} KB"
    }
}
