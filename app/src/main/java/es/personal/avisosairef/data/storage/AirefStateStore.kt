package es.personal.avisosairef.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.airefDataStore by preferencesDataStore(name = "airef_state")

interface StateStore {
    val state: Flow<AppState>
    suspend fun current(): AppState
    suspend fun update(transform: (AppState) -> AppState): AppState
    suspend fun resetReference(monitorId: String)
}

class AirefStateStore(private val context: Context) : StateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val state: Flow<AppState> = context.airefDataStore.data.map { prefs ->
        (prefs[STATE_JSON]?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() } ?: AppState()).withoutLegacyLabels()
    }

    override suspend fun current(): AppState = state.first()

    override suspend fun update(transform: (AppState) -> AppState): AppState {
        var updated = AppState()
        context.airefDataStore.edit { prefs ->
            val current = (prefs[STATE_JSON]?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() } ?: AppState()).withoutLegacyLabels()
            updated = transform(current)
            prefs[STATE_JSON] = json.encodeToString(updated)
        }
        return updated
    }

    override suspend fun resetReference(monitorId: String) {
        update {
            it.copy(
                monitors = it.monitors.map { monitor ->
                    if (monitor.id != monitorId) {
                        monitor
                    } else {
                        monitor.copy(
                            knownPublications = emptyList(),
                            unseenPublications = emptyList(),
                            recentPublications = emptyList(),
                            eTag = null,
                            lastModified = null,
                            lastChangeAtMillis = null,
                            lastError = null,
                            lastResult = "Referencia pendiente"
                        )
                    }
                }
            )
        }
    }

    private companion object {
        val STATE_JSON = stringPreferencesKey("state_json")
    }
}

private fun AppState.withoutLegacyLabels(): AppState {
    val migratedMonitors = monitors.map { monitor ->
            if (monitor.id == "default_airef") {
                monitor.copy(
                    name = "Pagina principal",
                    folder = "General"
                )
            } else {
                monitor
            }
        }
    val existingNames = groups.map { it.name }.toSet()
    val missingGroups = migratedMonitors
        .map { it.folder.ifBlank { "General" } }
        .distinct()
        .filterNot { it in existingNames }
        .map { MonitorGroup(it, defaultColorForGroup(it)) }
    return copy(
        monitors = migratedMonitors,
        groups = (groups.ifEmpty { listOf(MonitorGroup.default()) } + missingGroups).distinctBy { it.name }
    )
}

private fun defaultColorForGroup(name: String): String {
    val palette = listOf("#063347", "#2F6F73", "#4F6F52", "#6B5B7A", "#8A5A44", "#3F5F8A")
    return palette[kotlin.math.abs(name.lowercase().hashCode()) % palette.size]
}
