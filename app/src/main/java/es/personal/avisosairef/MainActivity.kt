package es.personal.avisosairef

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import es.personal.avisosairef.data.storage.AppState
import es.personal.avisosairef.data.storage.WebMonitor
import es.personal.avisosairef.ui.theme.RefrescoWebTheme
import es.personal.avisosairef.worker.AirefWorkScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ServiceLocator.repository(this)
        val viewModel = ViewModelProvider(this, MainViewModel.factory(repository))[MainViewModel::class.java]
        setContent {
            RefrescoWebTheme {
                WebRefreshScreen(viewModel)
            }
        }
    }
}

class MainViewModel(
    private val repository: es.personal.avisosairef.data.repository.AirefRepository
) : ViewModel() {
    val state: StateFlow<AppState> = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())
    var checkingId by mutableStateOf<String?>(null)
        private set
    var settingsError by mutableStateOf<String?>(null)
        private set

    fun setMonitoring(context: android.content.Context, enabled: Boolean) {
        viewModelScope.launch {
            repository.setMonitoringEnabled(enabled)
            if (enabled) {
                AirefWorkScheduler.schedulePeriodic(context, repository.nextPeriodicIntervalMinutes(state.value))
            } else {
                AirefWorkScheduler.cancelPeriodic(context)
            }
        }
    }

    fun updateSettings(defaultIntervalMinutes: Long, telegramEnabled: Boolean, botToken: String, chatId: String) {
        viewModelScope.launch {
            repository.updateDefaultSettings(defaultIntervalMinutes)
            repository.updateTelegramSettings(telegramEnabled, botToken, chatId)
        }
    }

    fun saveMonitor(context: android.content.Context, draft: MonitorDraft) {
        viewModelScope.launch {
            settingsError = null
            runCatching {
                repository.upsertMonitor(
                    id = draft.id,
                    name = draft.name,
                    folder = draft.type,
                    url = draft.url,
                    intervalMinutes = draft.intervalMinutes,
                    enabled = draft.enabled,
                    cssSelector = draft.cssSelector,
                    includeKeywords = draft.includeKeywords
                )
                if (state.value.monitoringEnabled) {
                    AirefWorkScheduler.schedulePeriodic(context, repository.nextPeriodicIntervalMinutes(state.value))
                }
            }.onFailure {
                settingsError = it.message ?: "No se pudieron guardar los ajustes."
            }
        }
    }

    fun setMonitorEnabled(context: android.content.Context, id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setMonitorEnabled(id, enabled)
            if (state.value.monitoringEnabled) {
                AirefWorkScheduler.schedulePeriodic(context, repository.nextPeriodicIntervalMinutes(state.value))
            }
        }
    }

    fun deleteMonitor(context: android.content.Context, id: String) {
        viewModelScope.launch {
            repository.deleteMonitor(id)
            if (state.value.monitoringEnabled) {
                AirefWorkScheduler.schedulePeriodic(context, repository.nextPeriodicIntervalMinutes(state.value))
            }
        }
    }

    fun checkNow(monitorId: String) {
        viewModelScope.launch {
            checkingId = monitorId
            runCatching { repository.checkNow(monitorId) }
            checkingId = null
        }
    }

    companion object {
        fun factory(repository: es.personal.avisosairef.data.repository.AirefRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
            }
    }
}

data class MonitorDraft(
    val id: String?,
    val name: String,
    val type: String,
    val url: String,
    val intervalMinutes: Long,
    val enabled: Boolean,
    val cssSelector: String,
    val includeKeywords: String
) {
    companion object {
        fun from(monitor: WebMonitor): MonitorDraft = MonitorDraft(
            id = monitor.id,
            name = monitor.name,
            type = monitor.folder,
            url = monitor.monitoredUrl,
            intervalMinutes = monitor.intervalMinutes,
            enabled = monitor.enabled,
            cssSelector = monitor.cssSelector,
            includeKeywords = monitor.includeKeywords
        )

        fun empty(defaultIntervalMinutes: Long): MonitorDraft = MonitorDraft(
            id = null,
            name = "",
            type = "General",
            url = "https://",
            intervalMinutes = defaultIntervalMinutes,
            enabled = true,
            cssSelector = "",
            includeKeywords = ""
        )
    }
}

@Composable
private fun WebRefreshScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var editDraft by remember { mutableStateOf<MonitorDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<WebMonitor?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(state.monitoringEnabled) {
        if (state.monitoringEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${state.monitors.size} paginas configuradas", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { showSettings = true }) { Text("Ajustes") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Comprobaciones automaticas", fontWeight = FontWeight.SemiBold)
                            Switch(checked = state.monitoringEnabled, onCheckedChange = { viewModel.setMonitoring(context, it) })
                        }
                        Text("Activa o detiene todas las revisiones en segundo plano.")
                    }
                }
            }
            item {
                Button(onClick = { editDraft = MonitorDraft.empty(state.defaultIntervalMinutes) }) {
                    Text("Anadir pagina")
                }
            }
            state.monitors.groupBy { it.folder.ifBlank { "General" } }.forEach { (type, monitors) ->
                item {
                    TypeCard(
                        type = type,
                        color = colorForType(type),
                        monitors = monitors,
                        checkingId = viewModel.checkingId,
                        onEdit = { editDraft = MonitorDraft.from(it) },
                        onRefresh = { viewModel.checkNow(it.id) },
                        onOpen = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.monitoredUrl))) },
                        onDelete = { deleteTarget = it },
                        onEnabledChange = { monitor, enabled -> viewModel.setMonitorEnabled(context, monitor.id, enabled) }
                    )
                }
            }
        }
    }

    editDraft?.let { draft ->
        MonitorEditorDialog(
            initial = draft,
            error = viewModel.settingsError,
            onDismiss = { editDraft = null },
            onSave = {
                viewModel.saveMonitor(context, it)
                editDraft = null
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onSave = { defaultInterval, telegramEnabled, token, chatId ->
                viewModel.updateSettings(defaultInterval, telegramEnabled, token, chatId)
                showSettings = false
            }
        )
    }

    deleteTarget?.let { monitor ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar pagina") },
            text = { Text("Se eliminara '${monitor.name}' y su historial local.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMonitor(context, monitor.id)
                    deleteTarget = null
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun TypeCard(
    type: String,
    color: Color,
    monitors: List<WebMonitor>,
    checkingId: String?,
    onEdit: (WebMonitor) -> Unit,
    onRefresh: (WebMonitor) -> Unit,
    onOpen: (WebMonitor) -> Unit,
    onDelete: (WebMonitor) -> Unit,
    onEnabledChange: (WebMonitor, Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(color)
            )
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(type, fontWeight = FontWeight.Bold, color = color)
                monitors.forEach { monitor ->
                    PageCard(
                        monitor = monitor,
                        isChecking = checkingId == monitor.id,
                        onEdit = { onEdit(monitor) },
                        onRefresh = { onRefresh(monitor) },
                        onOpen = { onOpen(monitor) },
                        onDelete = { onDelete(monitor) },
                        onEnabledChange = { onEnabledChange(monitor, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PageCard(
    monitor: WebMonitor,
    isChecking: Boolean,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(monitor.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = monitor.enabled, onCheckedChange = onEnabledChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit) { Text("Editar") }
                OutlinedButton(onClick = onRefresh, enabled = !isChecking) { Text(if (isChecking) "..." else "Refrescar") }
                OutlinedButton(onClick = onOpen) { Text("Abrir") }
                TextButton(onClick = onDelete) { Text("Eliminar") }
            }
        }
    }
}

@Composable
private fun MonitorEditorDialog(
    initial: MonitorDraft,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (MonitorDraft) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var url by remember { mutableStateOf(initial.url) }
    var interval by remember { mutableStateOf(initial.intervalMinutes) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var cssSelector by remember { mutableStateOf(initial.cssSelector) }
    var includeKeywords by remember { mutableStateOf(initial.includeKeywords) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "Anadir pagina" else "Editar pagina") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre de la tarjeta") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text("Tipo") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL HTTPS") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4) }
                item {
                    Text("Frecuencia aproximada", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervalOptions.forEach { option ->
                            if (interval == option) Button(onClick = { interval = option }) { Text(option.intervalLabel()) }
                            else OutlinedButton(onClick = { interval = option }) { Text(option.intervalLabel()) }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Pagina activa")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
                item {
                    OutlinedTextField(
                        value = cssSelector,
                        onValueChange = { cssSelector = it },
                        label = { Text("Selector CSS opcional") },
                        supportingText = { Text("Ejemplo: main article, .contenido, #resultados") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = includeKeywords,
                        onValueChange = { includeKeywords = it },
                        label = { Text("Palabras clave opcionales") },
                        supportingText = { Text("Separadas por coma. Si se rellena, solo avisa de enlaces que coincidan.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    MonitorDraft(
                        id = initial.id,
                        name = name,
                        type = type,
                        url = url,
                        intervalMinutes = interval,
                        enabled = enabled,
                        cssSelector = cssSelector,
                        includeKeywords = includeKeywords
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SettingsDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onSave: (Long, Boolean, String, String) -> Unit
) {
    var defaultInterval by remember { mutableStateOf(state.defaultIntervalMinutes) }
    var telegramEnabled by remember { mutableStateOf(state.telegramEnabled) }
    var telegramToken by remember { mutableStateOf(state.telegramBotToken) }
    var telegramChatId by remember { mutableStateOf(state.telegramChatId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Frecuencia por defecto", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervalOptions.forEach { option ->
                            if (defaultInterval == option) Button(onClick = { defaultInterval = option }) { Text(option.intervalLabel()) }
                            else OutlinedButton(onClick = { defaultInterval = option }) { Text(option.intervalLabel()) }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Avisos por Telegram")
                        Switch(checked = telegramEnabled, onCheckedChange = { telegramEnabled = it })
                    }
                }
                item { OutlinedTextField(value = telegramToken, onValueChange = { telegramToken = it }, label = { Text("Bot token") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = telegramChatId, onValueChange = { telegramChatId = it }, label = { Text("Chat ID") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("Telegram es opcional y envia los avisos directamente desde el telefono.") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(defaultInterval, telegramEnabled, telegramToken, telegramChatId) }) {
                Text("Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private val intervalOptions = listOf(15L, 30L, 60L, 120L)

private fun Long.intervalLabel(): String = when (this) {
    15L -> "15 min"
    30L -> "30 min"
    60L -> "1 h"
    120L -> "2 h"
    else -> "$this min"
}

private fun colorForType(type: String): Color {
    val palette = listOf(
        Color(0xFF063347),
        Color(0xFF2F6F73),
        Color(0xFF4F6F52),
        Color(0xFF6B5B7A),
        Color(0xFF8A5A44),
        Color(0xFF3F5F8A)
    )
    return palette[type.lowercase().hashCode().absoluteValue % palette.size]
}
