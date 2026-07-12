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
        prefs[STATE_JSON]?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() } ?: AppState()
    }

    override suspend fun current(): AppState = state.first()

    override suspend fun update(transform: (AppState) -> AppState): AppState {
        var updated = AppState()
        context.airefDataStore.edit { prefs ->
            val current = prefs[STATE_JSON]?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() } ?: AppState()
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
