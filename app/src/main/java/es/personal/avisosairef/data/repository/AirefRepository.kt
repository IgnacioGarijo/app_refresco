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
import es.personal.avisosairef.data.storage.WebMonitor
import kotlinx.coroutines.flow.Flow
import java.util.UUID

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

    suspend fun updateTelegramSettings(enabled: Boolean, botToken: String, chatId: String) {
        store.update {
            it.copy(
                telegramEnabled = enabled,
                telegramBotToken = botToken.trim(),
                telegramChatId = chatId.trim()
            )
        }
    }

    suspend fun updateDefaultSettings(defaultIntervalMinutes: Long) {
        store.update { it.copy(defaultIntervalMinutes = defaultIntervalMinutes.coerceAtLeast(15)) }
    }

    suspend fun selectMonitor(monitorId: String) {
        store.update { state ->
            if (state.monitors.any { it.id == monitorId }) state.copy(selectedMonitorId = monitorId) else state
        }
    }

    suspend fun setMonitorEnabled(monitorId: String, enabled: Boolean) {
        store.update { state ->
            state.copy(monitors = state.monitors.map { if (it.id == monitorId) it.copy(enabled = enabled) else it })
        }
    }

    suspend fun upsertMonitor(
        id: String?,
        name: String,
        folder: String,
        url: String,
        intervalMinutes: Long,
        enabled: Boolean,
        cssSelector: String,
        includeKeywords: String
    ): String {
        val cleanUrl = url.trim()
        require(cleanUrl.startsWith("https://")) { "La URL debe empezar por https://" }
        require(name.trim().isNotBlank()) { "El nombre no puede estar vacio." }

        val cleanId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        store.update { state ->
            val existing = state.monitors.firstOrNull { it.id == cleanId }
            val updated = if (existing == null) {
                WebMonitor(
                    id = cleanId,
                    name = name.trim(),
                    folder = folder.trim().ifBlank { "Personal" },
                    monitoredUrl = cleanUrl,
                    intervalMinutes = intervalMinutes,
                    enabled = enabled,
                    cssSelector = cssSelector.trim(),
                    includeKeywords = includeKeywords.trim()
                )
            } else {
                val urlChanged = existing.monitoredUrl != cleanUrl
                existing.copy(
                    name = name.trim(),
                    folder = folder.trim().ifBlank { "Personal" },
                    monitoredUrl = cleanUrl,
                    intervalMinutes = intervalMinutes,
                    enabled = enabled,
                    cssSelector = cssSelector.trim(),
                    includeKeywords = includeKeywords.trim(),
                    knownPublications = if (urlChanged) emptyList() else existing.knownPublications,
                    unseenPublications = if (urlChanged) emptyList() else existing.unseenPublications,
                    recentPublications = if (urlChanged) emptyList() else existing.recentPublications,
                    eTag = if (urlChanged) null else existing.eTag,
                    lastModified = if (urlChanged) null else existing.lastModified,
                    lastSuccessAtMillis = if (urlChanged) null else existing.lastSuccessAtMillis,
                    lastChangeAtMillis = if (urlChanged) null else existing.lastChangeAtMillis,
                    lastError = if (urlChanged) null else existing.lastError,
                    lastResult = if (urlChanged) "Referencia pendiente: URL actualizada." else existing.lastResult
                )
            }
            state.copy(
                monitors = (state.monitors.filterNot { it.id == cleanId } + updated).sortedWith(compareBy<WebMonitor> { it.folder.lowercase() }.thenBy { it.name.lowercase() }),
                selectedMonitorId = cleanId
            )
        }
        return cleanId
    }

    suspend fun deleteMonitor(monitorId: String) {
        store.update { state ->
            val remaining = state.monitors.filterNot { it.id == monitorId }.ifEmpty { listOf(WebMonitor.default()) }
            state.copy(
                monitors = remaining,
                selectedMonitorId = remaining.firstOrNull { it.id == state.selectedMonitorId }?.id ?: remaining.first().id
            )
        }
    }

    suspend fun resetReference(monitorId: String) {
        store.resetReference(monitorId)
    }

    suspend fun markUnseenAsViewed(monitorId: String) {
        store.update { state ->
            state.copy(monitors = state.monitors.map { if (it.id == monitorId) it.copy(unseenPublications = emptyList()) else it })
        }
    }

    suspend fun checkNow(monitorId: String? = null): CheckOutcome {
        val state = store.current()
        val monitor = monitorId?.let { id -> state.monitors.firstOrNull { it.id == id } } ?: state.selectedMonitor
        return checkMonitor(monitor)
    }

    suspend fun checkDueMonitors(): List<CheckOutcome> {
        val state = store.current()
        if (!state.monitoringEnabled) return emptyList()
        val now = clock()
        val due = state.monitors.filter { monitor ->
            monitor.enabled && ((monitor.lastAttemptAtMillis ?: 0L) + monitor.intervalMinutes.coerceAtLeast(15) * 60_000L <= now)
        }
        return due.map { checkMonitor(it) }
    }

    fun nextPeriodicIntervalMinutes(state: AppState): Long =
        state.monitors.filter { it.enabled }.minOfOrNull { it.intervalMinutes.coerceAtLeast(15) } ?: Constants.DefaultIntervalMinutes

    private suspend fun checkMonitor(monitor: WebMonitor): CheckOutcome {
        val now = clock()
        updateMonitor(monitor.id) { it.copy(lastAttemptAtMillis = now) }

        return when (val fetch = httpClient.fetch(monitor.monitoredUrl, monitor.eTag, monitor.lastModified)) {
            is FetchResult.NotModified -> {
                updateMonitor(monitor.id) {
                    it.copy(
                        eTag = fetch.eTag,
                        lastModified = fetch.lastModified,
                        lastSuccessAtMillis = now,
                        lastError = null,
                        lastResult = "Sin cambios: el servidor respondio 304."
                    )
                }
                CheckOutcome(CheckStatus.NotModified, "Sin cambios", monitor.id, monitor.name)
            }

            is FetchResult.Failure -> {
                val message = fetch.error.toUserMessage()
                updateMonitor(monitor.id) { it.copy(lastError = message, lastResult = message) }
                CheckOutcome(CheckStatus.Error, message, monitor.id, monitor.name, shouldRetry = fetch.error is FetchError.Network || fetch.error is FetchError.Http)
            }

            is FetchResult.Success -> handleHtml(monitor, fetch, now)
        }
    }

    private suspend fun handleHtml(monitor: WebMonitor, fetch: FetchResult.Success, now: Long): CheckOutcome {
        return when (val parsed = parser.parse(
            fetch.html,
            monitor.monitoredUrl,
            previousKnownCount = monitor.knownPublications.size,
            cssSelector = monitor.cssSelector,
            includeKeywords = monitor.includeKeywords
        )) {
            is ParserResult.Failure -> {
                val message = "Error de parsing: ${parsed.message}"
                updateMonitor(monitor.id) {
                    it.copy(
                        eTag = fetch.eTag,
                        lastModified = fetch.lastModified,
                        lastError = message,
                        lastResult = message
                    )
                }
                CheckOutcome(CheckStatus.Error, message, monitor.id, monitor.name)
            }

            is ParserResult.Success -> {
                val current = parsed.publications
                if (monitor.knownPublications.isEmpty()) {
                    val message = "Referencia inicial creada: ${current.size} enlaces encontrados."
                    updateMonitor(monitor.id) {
                        it.copy(
                            knownPublications = current,
                            eTag = fetch.eTag,
                            lastModified = fetch.lastModified,
                            lastSuccessAtMillis = now,
                            lastError = null,
                            lastResult = message
                        )
                    }
                    return CheckOutcome(CheckStatus.Success, message, monitor.id, monitor.name, firstReferenceCreated = true)
                }

                val knownKeys = monitor.knownPublications.map { it.key }.toSet()
                val newItems = current.filterNot { it.key in knownKeys }
                val recent = (newItems + monitor.recentPublications).distinctBy { it.key }.take(20)
                val message = if (newItems.isEmpty()) "Comprobacion correcta: sin novedades." else "Novedades detectadas: ${newItems.size}."
                updateMonitor(monitor.id) {
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
                CheckOutcome(CheckStatus.Success, if (newItems.isEmpty()) "Sin novedades" else "${newItems.size} novedades", monitor.id, monitor.name, newItems)
            }
        }
    }

    private suspend fun updateMonitor(monitorId: String, transform: (WebMonitor) -> WebMonitor) {
        store.update { state ->
            state.copy(monitors = state.monitors.map { if (it.id == monitorId) transform(it) else it })
        }
    }

    private fun FetchError.toUserMessage(): String = when (this) {
        is FetchError.Network -> "Error de red: $message"
        is FetchError.Tls -> "Error TLS/certificado: $message"
        is FetchError.Http -> "Respuesta HTTP no valida: $code"
        is FetchError.TooLarge -> "Respuesta demasiado grande; limite ${maxBytes / 1024} KB"
    }
}
