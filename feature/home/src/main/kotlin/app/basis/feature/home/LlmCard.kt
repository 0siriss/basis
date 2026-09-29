package app.basis.feature.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.basis.core.datastore.LlmPrefs
import app.basis.core.datastore.LlmSettings
import app.basis.ml.llm.LlmModel
import app.basis.ml.models.ModelManager
import app.basis.ml.models.ModelState
import app.basis.pipeline.TranscriptionScheduler
import app.basis.pipeline.summary.SummaryState
import app.basis.pipeline.summary.SummaryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LlmViewModel @Inject constructor(
    private val prefs: LlmPrefs,
    private val models: ModelManager,
    private val scheduler: TranscriptionScheduler,
    status: SummaryStatus,
) : ViewModel() {
    val settings: StateFlow<LlmSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmSettings())
    val state: StateFlow<SummaryState> = status.state
    val modelStates: Map<LlmModel, StateFlow<ModelState>> = LlmModel.entries.associateWith { models.state(it.spec) }
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun select(m: LlmModel) = viewModelScope.launch { prefs.setModel(m.spec.id) }
    fun download(m: LlmModel) = models.download(m.spec)
    fun delete(m: LlmModel) = models.delete(m.spec)
    fun import(uri: Uri) = viewModelScope.launch { _message.value = models.import(uri) }
    fun consumeMessage() { _message.value = null }
    fun setThreads(n: Int) = viewModelScope.launch { prefs.setThreads(n) }
    fun summarizeNow() = scheduler.summarizeNow()
}

@Composable
fun LlmCard(vm: LlmViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeMessage() } }
    val selected = LlmModel.byId(settings.modelId)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Сводки (локальная LLM)", style = MaterialTheme.typography.titleMedium)
            LlmModel.entries.forEach { m ->
                val ms by vm.modelStates.getValue(m).collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m == selected, onClick = { vm.select(m) })
                    Text(m.spec.title)
                }
                if (m == selected || ms != ModelState.Missing) {
                    Column(Modifier.padding(start = 48.dp)) {
                        ModelRow(m.spec, ms, onDownload = { vm.download(m) }, onImport = { picker.launch(arrayOf("*/*")) }, onDelete = { vm.delete(m) })
                    }
                }
            }
            Text("Потоков CPU: ${settings.threads}, контекст ${settings.contextSize} токенов")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6, 8).forEach { n -> FilterChip(selected = settings.threads == n, onClick = { vm.setThreads(n) }, label = { Text("$n") }) }
            }
            HorizontalDivider()
            if (state.running) {
                Text("Идёт построение сводки: ${state.step}")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                state.last?.let { Text("Последний запуск: $it", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::summarizeNow) { Text("Сводка сейчас") }
                Text(
                    "Обычно сводки строятся сами после распознавания (на зарядке): по завершённым часам и итог — после конца дня. " +
                        "«Сейчас» включает текущий час и день. Скорость — в логах, тег Summary.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
