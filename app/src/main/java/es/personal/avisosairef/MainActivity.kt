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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import es.personal.avisosairef.data.repository.CheckStatus
import es.personal.avisosairef.data.storage.AppState
import es.personal.avisosairef.ui.theme.AvisosAirefTheme
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
            AvisosAirefTheme {
                AirefScreen(viewModel)
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

    fun setMonitoring(context: android.content.Context, enabled: Boolean) {
        viewModelScope.launch {
            repository.setMonitoringEnabled(enabled)
            if (enabled) AirefWorkScheduler.schedulePeriodic(context) else AirefWorkScheduler.cancelPeriodic(context)
        }
    }

    fun checkNow() {
        viewModelScope.launch {
            checking = true
            runCatching { repository.checkNow() }
            checking = false
        }
    }

    fun resetReference() {
        viewModelScope.launch { repository.resetReference() }
    }

    companion object {
        fun factory(repository: es.personal.avisosairef.data.repository.AirefRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
            }
    }
}

@Composable
private fun AirefScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
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
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(Constants.PageUrl, style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Apartado vigilado", fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.target_section))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.monitoring_active))
                            Switch(checked = state.monitoringEnabled, onCheckedChange = { viewModel.setMonitoring(context, it) })
                        }
                        Text("Frecuencia: aproximadamente cada 30 minutos")
                        Text("Android puede retrasar las comprobaciones para ahorrar batería.")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Estado actual: ${statusText(state)}", fontWeight = FontWeight.SemiBold)
                        Text("Última comprobación intentada: ${state.lastAttemptAtMillis.formatDate()}")
                        Text("Última comprobación correcta: ${state.lastSuccessAtMillis.formatDate()}")
                        Text("Resultado: ${state.lastResult}")
                        Text("Último cambio detectado: ${state.lastChangeAtMillis.formatDate()}")
                        Text("Documentos conocidos: ${state.knownPublications.size}")
                        state.lastError?.let { Text("Último error: $it", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.checkNow() }, enabled = !viewModel.checking) {
                        Text(stringResource(R.string.check_now))
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.PageUrl)))
                    }) {
                        Text(stringResource(R.string.open_airef))
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
            item {
                Text("Últimos documentos detectados", style = MaterialTheme.typography.titleMedium)
            }
            if (state.recentPublications.isEmpty()) {
                item { Text("Aún no hay novedades posteriores a la referencia inicial.") }
            } else {
                items(state.recentPublications) { publication ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(publication.title, fontWeight = FontWeight.SemiBold)
                            Text(publication.type ?: "Enlace", style = MaterialTheme.typography.bodySmall)
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetReference()
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun statusText(state: AppState): String = when {
    !state.monitoringEnabled -> "detenida"
    state.lastError != null -> "error"
    state.knownPublications.isEmpty() -> "referencia pendiente"
    else -> "activa"
}

private fun Long?.formatDate(): String =
    this?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Nunca"
