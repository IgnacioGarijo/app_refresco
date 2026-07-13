package es.personal.avisosairef.data.repository

import es.personal.avisosairef.Constants
import es.personal.avisosairef.data.network.AirefFetcher
import es.personal.avisosairef.data.network.AirefHttpClient
import es.personal.avisosairef.data.network.FetchError
import es.personal.avisosairef.data.network.FetchResult
import es.personal.avisosairef.data.parser.AirefPublicationsParser
import es.personal.avisosairef.data.parser.PageSnapshot
import es.personal.avisosairef.data.parser.ParserResult
import es.personal.avisosairef.data.parser.Publicacion
import es.personal.avisosairef.data.storage.AppState
import es.personal.avisosairef.data.storage.MonitorGroup
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
        store.update { state ->
            if (enabled) {
                val restoreIds = state.pausedMonitorIds.toSet()
                state.copy(
                    monitoringEnabled = true,
                    monitors = state.monitors.map { monitor ->
                        if (monitor.id in restoreIds) monitor.copy(enabled = true) else monitor
                    },
                    pausedMonitorIds = emptyList()
                )
            } else {
                state.copy(
                    monitoringEnabled = false,
                    pausedMonitorIds = state.monitors.filter { it.enabled }.map { it.id },
                    monitors = state.monitors.map { it.copy(enabled = false) }
                )
            }
        }
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

    suspend fun upsertGroup(originalName: String?, name: String, colorHex: String) {
        val cleanName = name.trim().ifBlank { "General" }
        val cleanColor = colorHex.trim().ifBlank { "#063347" }
        store.update { state ->
            val original = originalName?.trim().orEmpty()
            val existing = state.groups.firstOrNull { it.name == original || it.name == cleanName }
            val updatedGroup = (existing ?: MonitorGroup(cleanName, cleanColor)).copy(name = cleanName, colorHex = cleanColor)
            state.copy(
                groups = (state.groups.filterNot { it.name == original || it.name == cleanName } + updatedGroup)
                    .sortedBy { it.name.lowercase() },
                monitors = state.monitors.map { monitor ->
                    if (original.isNotBlank() && monitor.folder == original) monitor.copy(folder = cleanName) else monitor
                }
            )
        }
    }

    suspend fun toggleGroupCollapsed(name: String) {
        store.update { state ->
            state.copy(groups = state.groups.map { if (it.name == name) it.copy(collapsed = !it.collapsed) else it })
        }
    }

    suspend fun deleteGroup(name: String) {
        val cleanName = name.trim()
        store.update { state ->
            val remainingMonitors = state.monitors.filterNot { it.folder == cleanName }
            state.copy(
                monitors = remainingMonitors,
                groups = state.groups.filterNot { it.name == cleanName },
                selectedMonitorId = remainingMonitors.firstOrNull { it.id == state.selectedMonitorId }?.id
                    ?: remainingMonitors.firstOrNull()?.id
                    ?: Constants.DefaultMonitorId
            )
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
                    lastResult = if (urlChanged) "Referencia pendiente: URL actualizada." else existing.lastResult,
                    lastSnapshot = if (urlChanged) null else existing.lastSnapshot,
                    lastDiagnostics = if (urlChanged) "URL actualizada; se creara una nueva referencia." else existing.lastDiagnostics
                )
            }
            state.copy(
                monitors = (state.monitors.filterNot { it.id == cleanId } + updated).sortedWith(compareBy<WebMonitor> { it.folder.lowercase() }.thenBy { it.name.lowercase() }),
                groups = ensureGroupExists(state.groups, updated.folder),
                selectedMonitorId = cleanId
            )
        }
        return cleanId
    }

    suspend fun deleteMonitor(monitorId: String) {
        store.update { state ->
            val removedFolder = state.monitors.firstOrNull { it.id == monitorId }?.folder
            val remaining = state.monitors.filterNot { it.id == monitorId }
            val remainingGroups = if (removedFolder != null && remaining.none { it.folder == removedFolder }) {
                state.groups.filterNot { it.name == removedFolder }
            } else {
                state.groups
            }
            state.copy(
                monitors = remaining,
                groups = remainingGroups,
                selectedMonitorId = remaining.firstOrNull { it.id == state.selectedMonitorId }?.id
                    ?: remaining.firstOrNull()?.id
                    ?: Constants.DefaultMonitorId
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
                        lastResult = "Sin cambios: el servidor respondio 304.",
                        lastDiagnostics = diagnosticsForNotModified(fetch.eTag, fetch.lastModified)
                    )
                }
                CheckOutcome(CheckStatus.NotModified, "Sin cambios", monitor.id, monitor.name)
            }

            is FetchResult.Failure -> {
                val message = fetch.error.toUserMessage()
                updateMonitor(monitor.id) {
                    it.copy(
                        lastError = message,
                        lastResult = message,
                        lastDiagnostics = "Fallo de comprobacion. Estado anterior conservado. Motivo: $message"
                    )
                }
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
                        lastResult = message,
                        lastDiagnostics = "Fallo de parsing. Estado anterior conservado. Motivo: $message"
                    )
                }
                CheckOutcome(CheckStatus.Error, message, monitor.id, monitor.name)
            }

            is ParserResult.Success -> {
                val current = parsed.publications
                if (monitor.knownPublications.isEmpty() && monitor.lastSnapshot == null) {
                    val message = "Referencia inicial creada: ${current.size} enlaces y snapshot de pagina guardados."
                    updateMonitor(monitor.id) {
                        it.copy(
                            knownPublications = current,
                            lastSnapshot = parsed.snapshot,
                            eTag = fetch.eTag,
                            lastModified = fetch.lastModified,
                            lastSuccessAtMillis = now,
                            lastError = null,
                            lastResult = message,
                            lastDiagnostics = diagnosticsForSnapshot(parsed.snapshot, fetch, firstReference = true)
                        )
                    }
                    return CheckOutcome(CheckStatus.Success, message, monitor.id, monitor.name, firstReferenceCreated = true)
                }

                val knownKeys = monitor.knownPublications.map { it.key }.toSet()
                val newItems = current.filterNot { it.key in knownKeys }
                val changes = snapshotChanges(monitor.lastSnapshot, parsed.snapshot)
                val syntheticChanges = if (newItems.isEmpty() && changes.isNotEmpty()) {
                    listOf(snapshotChangePublication(monitor.monitoredUrl, changes, parsed.snapshot))
                } else {
                    emptyList()
                }
                val detectedItems = newItems + syntheticChanges
                val recent = (detectedItems + monitor.recentPublications).distinctBy { it.key }.take(20)
                val message = when {
                    detectedItems.isEmpty() -> "Comprobacion correcta: sin novedades."
                    newItems.isNotEmpty() && changes.isNotEmpty() -> "Novedades detectadas: ${newItems.size} enlaces y cambios en ${changes.joinToString(", ")}."
                    newItems.isNotEmpty() -> "Novedades detectadas: ${newItems.size} enlaces."
                    else -> "Cambio detectado en ${changes.joinToString(", ")}."
                }
                updateMonitor(monitor.id) {
                    it.copy(
                        knownPublications = current,
                        lastSnapshot = parsed.snapshot,
                        unseenPublications = (detectedItems + it.unseenPublications).distinctBy { pub -> pub.key },
                        recentPublications = recent,
                        eTag = fetch.eTag,
                        lastModified = fetch.lastModified,
                        lastSuccessAtMillis = now,
                        lastChangeAtMillis = if (detectedItems.isNotEmpty()) now else it.lastChangeAtMillis,
                        lastError = null,
                        lastResult = message,
                        lastDiagnostics = diagnosticsForSnapshot(parsed.snapshot, fetch, changes)
                    )
                }
                CheckOutcome(CheckStatus.Success, if (detectedItems.isEmpty()) "Sin novedades" else "${detectedItems.size} novedades", monitor.id, monitor.name, detectedItems)
            }
        }
    }

    private fun snapshotChanges(previous: PageSnapshot?, current: PageSnapshot): List<String> {
        if (previous == null) return emptyList()
        val changes = mutableListOf<String>()
        if (previous.textHash != current.textHash) changes += "texto visible"
        if (previous.domHash != current.domHash) changes += "estructura DOM"
        if (previous.linksHash != current.linksHash) changes += "enlaces"
        if (previous.metadataHash != current.metadataHash) changes += "metadatos"
        if (previous.imagesHash != current.imagesHash) changes += "imagenes/avatar"
        return changes
    }

    private fun snapshotChangePublication(url: String, changes: List<String>, snapshot: PageSnapshot): Publicacion {
        val title = "Cambio detectado: ${changes.joinToString(", ")}"
        val key = "snapshot:${snapshot.combinedHash}:$url"
        return Publicacion(
            title = title,
            normalizedTitle = title.lowercase(),
            url = url,
            key = key,
            type = "Cambio",
            detectedDate = null
        )
    }

    private fun diagnosticsForNotModified(eTag: String?, lastModified: String?): String =
        "HTTP 304 sin cambios. ETag=${eTag ?: "no disponible"}; Last-Modified=${lastModified ?: "no disponible"}."

    private fun diagnosticsForSnapshot(snapshot: PageSnapshot, fetch: FetchResult.Success, changes: List<String> = emptyList(), firstReference: Boolean = false): String =
        buildString {
            append(if (firstReference) "Referencia inicial. " else "Snapshot comparado. ")
            append("Cambios=${changes.ifEmpty { listOf("ninguno") }.joinToString(", ")}. ")
            append("Texto=${snapshot.visibleTextLength} caracteres; enlaces=${snapshot.linkCount}; imagenes=${snapshot.imageCount}; metadatos=${snapshot.metadataCount}. ")
            append("Hashes texto=${snapshot.textHash.take(12)}, dom=${snapshot.domHash.take(12)}, enlaces=${snapshot.linksHash.take(12)}, meta=${snapshot.metadataHash.take(12)}, imagenes=${snapshot.imagesHash.take(12)}. ")
            append("Bytes=${fetch.bytesRead.takeIf { it >= 0 } ?: fetch.html.length}. ")
            append("URL final=${fetch.finalUrl ?: "no disponible"}. ")
            append("ETag=${fetch.eTag ?: "no disponible"}; Last-Modified=${fetch.lastModified ?: "no disponible"}.")
            if (snapshot.dynamicHints.isNotEmpty()) {
                append(" Diagnostico dinamico: ${snapshot.dynamicHints.joinToString(" ")}")
            }
        }

    private suspend fun updateMonitor(monitorId: String, transform: (WebMonitor) -> WebMonitor) {
        store.update { state ->
            state.copy(monitors = state.monitors.map { if (it.id == monitorId) transform(it) else it })
        }
    }

    private fun ensureGroupExists(groups: List<MonitorGroup>, name: String): List<MonitorGroup> =
        if (groups.any { it.name == name }) {
            groups
        } else {
            (groups + MonitorGroup(name, "#063347")).sortedBy { it.name.lowercase() }
        }

    private fun FetchError.toUserMessage(): String = when (this) {
        is FetchError.Network -> "Error de red: $message"
        is FetchError.Tls -> "Error TLS/certificado: $message"
        is FetchError.Http -> "Respuesta HTTP no valida: $code"
        is FetchError.TooLarge -> "Respuesta demasiado grande; limite ${maxBytes / 1024} KB"
    }
}
