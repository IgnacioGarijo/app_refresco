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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import java.text.DateFormat
import java.util.Date

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
    var checking by mutableStateOf(false)
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

    fun updateTelegram(enabled: Boolean, botToken: String, chatId: String) {
        viewModelScope.launch { repository.updateTelegramSettings(enabled, botToken, chatId) }
    }

    fun selectMonitor(id: String) {
        viewModelScope.launch { repository.selectMonitor(id) }
    }

    fun saveMonitor(context: android.content.Context, draft: MonitorDraft) {
        viewModelScope.launch {
            settingsError = null
            runCatching {
                repository.upsertMonitor(
                    id = draft.id,
                    name = draft.name,
                    folder = draft.folder,
                    url = draft.url,
                    intervalMinutes = draft.intervalMinutes,
                    enabled = draft.enabled,
                    sectionFilterEnabled = draft.sectionFilterEnabled,
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
            checking = true
            runCatching { repository.checkNow(monitorId) }
            checking = false
        }
    }

    fun resetReference(monitorId: String) {
        viewModelScope.launch { repository.resetReference(monitorId) }
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
    val folder: String,
    val url: String,
    val intervalMinutes: Long,
    val enabled: Boolean,
    val sectionFilterEnabled: Boolean,
    val cssSelector: String,
    val includeKeywords: String
) {
    companion object {
        fun from(monitor: WebMonitor): MonitorDraft = MonitorDraft(
            id = monitor.id,
            name = monitor.name,
            folder = monitor.folder,
            url = monitor.monitoredUrl,
            intervalMinutes = monitor.intervalMinutes,
            enabled = monitor.enabled,
            sectionFilterEnabled = monitor.sectionFilterEnabled,
            cssSelector = monitor.cssSelector,
            includeKeywords = monitor.includeKeywords
        )

        fun empty(): MonitorDraft = MonitorDraft(
            id = null,
            name = "",
            folder = "Personal",
            url = "https://",
            intervalMinutes = 30,
            enabled = true,
            sectionFilterEnabled = false,
            cssSelector = "",
            includeKeywords = ""
        )
    }
}

@Composable
private fun WebRefreshScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val selected = state.selectedMonitor
    var showResetDialog by remember { mutableStateOf(false) }
    var editDraft by remember { mutableStateOf<MonitorDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<WebMonitor?>(null) }
    var telegramToken by remember { mutableStateOf(state.telegramBotToken) }
    var telegramChatId by remember { mutableStateOf(state.telegramChatId) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(state.monitoringEnabled) {
        if (state.monitoringEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.telegramBotToken) { telegramToken = state.telegramBotToken }
    LaunchedEffect(state.telegramChatId) { telegramChatId = state.telegramChatId }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${state.monitors.size} paginas configuradas", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.monitoring_active), fontWeight = FontWeight.SemiBold)
                            Switch(checked = state.monitoringEnabled, onCheckedChange = { viewModel.setMonitoring(context, it) })
                        }
                        Text("Android puede retrasar las comprobaciones. Cada pagina usa su propia frecuencia aproximada.")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { editDraft = MonitorDraft.empty() }) { Text("Anadir pagina") }
                    OutlinedButton(onClick = { editDraft = MonitorDraft.from(selected) }) { Text("Editar seleccionada") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Avisos por Telegram", fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = state.telegramEnabled,
                                onCheckedChange = { viewModel.updateTelegram(it, telegramToken, telegramChatId) }
                            )
                        }
                        OutlinedTextField(
                            value = telegramToken,
                            onValueChange = { telegramToken = it },
                            label = { Text("Bot token") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = telegramChatId,
                            onValueChange = { telegramChatId = it },
                            label = { Text("Chat ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.updateTelegram(state.telegramEnabled, telegramToken, telegramChatId) }) {
                                Text("Guardar Telegram")
                            }
                        }
                        Text("Opcional. Se envia directamente a api.telegram.org cuando hay novedades; no se usa servidor propio.")
                    }
                }
            }
            state.monitors.groupBy { it.folder.ifBlank { "Personal" } }.forEach { (folder, monitors) ->
                item { Text(folder, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(monitors, key = { it.id }) { monitor ->
                    MonitorCard(
                        monitor = monitor,
                        selected = monitor.id == selected.id,
                        onSelect = { viewModel.selectMonitor(monitor.id) },
                        onEdit = { editDraft = MonitorDraft.from(monitor) },
                        onDelete = { deleteTarget = monitor }
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(selected.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(selected.monitoredUrl, style = MaterialTheme.typography.bodySmall)
                        Text("Estado actual: ${statusText(state, selected)}", fontWeight = FontWeight.SemiBold)
                        Text("Ultima comprobacion intentada: ${selected.lastAttemptAtMillis.formatDate()}")
                        Text("Ultima comprobacion correcta: ${selected.lastSuccessAtMillis.formatDate()}")
                        Text("Resultado: ${selected.lastResult}")
                        Text("Ultimo cambio detectado: ${selected.lastChangeAtMillis.formatDate()}")
                        Text("Enlaces conocidos: ${selected.knownPublications.size}")
                        selected.lastError?.let { Text("Ultimo error: $it", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.checkNow(selected.id) }, enabled = !viewModel.checking) {
                        Text(stringResource(R.string.check_now))
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selected.monitoredUrl)))
                    }) {
                        Text(stringResource(R.string.open_page))
                    }
                }
                if (viewModel.checking) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        Text("Comprobando...")
                    }
                }
            }
            item { Text("Ultimos cambios de ${selected.name}", style = MaterialTheme.typography.titleMedium) }
            if (selected.recentPublications.isEmpty()) {
                item { Text("Aun no hay novedades posteriores a la referencia inicial.") }
            } else {
                items(selected.recentPublications) { publication ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(publication.title, fontWeight = FontWeight.SemiBold)
                            Text(publication.type ?: "Enlace", style = MaterialTheme.typography.bodySmall)
                            Text(publication.url, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showResetDialog = true }) {
                    Text(stringResource(R.string.reset_reference))
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetReference(selected.id)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun MonitorCard(
    monitor: WebMonitor,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(monitor.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                    Text("${monitor.intervalMinutes.intervalLabel()} · ${if (monitor.enabled) "activa" else "pausada"} · ${monitor.knownPublications.size} enlaces", style = MaterialTheme.typography.bodySmall)
                }
                if (selected) Text("Seleccionada", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Text(monitor.monitoredUrl, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelect) { Text("Ver") }
                OutlinedButton(onClick = onEdit) { Text("Editar") }
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
    var folder by remember { mutableStateOf(initial.folder) }
    var url by remember { mutableStateOf(initial.url) }
    var interval by remember { mutableStateOf(initial.intervalMinutes) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var sectionFilter by remember { mutableStateOf(initial.sectionFilterEnabled) }
    var cssSelector by remember { mutableStateOf(initial.cssSelector) }
    var includeKeywords by remember { mutableStateOf(initial.includeKeywords) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "Anadir pagina" else "Editar pagina") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("Carpeta o etiqueta") }, modifier = Modifier.fillMaxWidth()) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                        Text("Pagina activa")
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
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sectionFilter, onCheckedChange = { sectionFilter = it })
                        Text("Usar filtro AIReF preconfigurado")
                    }
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
                        folder = folder,
                        url = url,
                        intervalMinutes = interval,
                        enabled = enabled,
                        sectionFilterEnabled = sectionFilter,
                        cssSelector = cssSelector,
                        includeKeywords = includeKeywords
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun statusText(state: AppState, monitor: WebMonitor): String = when {
    !state.monitoringEnabled || !monitor.enabled -> "detenida"
    monitor.lastError != null -> "error"
    monitor.knownPublications.isEmpty() -> "referencia pendiente"
    else -> "activa"
}

private fun Long?.formatDate(): String =
    this?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Nunca"

private val intervalOptions = listOf(15L, 30L, 60L, 120L)

private fun Long.intervalLabel(): String = when (this) {
    15L -> "15 min"
    30L -> "30 min"
    60L -> "1 h"
    120L -> "2 h"
    else -> "$this min"
}
