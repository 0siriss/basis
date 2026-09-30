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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.basis.core.database.ChunkDao
import app.basis.core.database.IndexStats
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import app.basis.ml.models.ModelState
import app.basis.pipeline.TranscriptionScheduler
import app.basis.pipeline.search.IndexState
import app.basis.pipeline.search.IndexStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val models: ModelManager,
    private val scheduler: TranscriptionScheduler,
    chunks: ChunkDao,
    status: IndexStatus,
) : ViewModel() {
    val spec = ModelCatalog.BGE_M3
    val modelState: StateFlow<ModelState> = models.state(spec)
    val stats: StateFlow<IndexStats> = chunks.observeStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexStats(0, 0, 0))
    val state: StateFlow<IndexState> = status.state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun download() = models.download(spec)
    fun delete() = models.delete(spec)
    fun import(uri: Uri) = viewModelScope.launch { _message.value = models.import(uri) }
    fun consumeMessage() { _message.value = null }
    fun indexNow() = scheduler.enqueueIndex(manual = true)
}

@Composable
fun SearchCard(onOpenChat: () -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val ms by vm.modelState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeMessage() } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Поиск и вопросы к дневнику", style = MaterialTheme.typography.titleMedium)
            Text(vm.spec.title)
            ModelRow(vm.spec, ms, onDownload = vm::download, onImport = { picker.launch(arrayOf("*/*")) }, onDelete = vm::delete)
            if (ms != ModelState.Ready) {
                Text(
                    "Без этой модели поиск работает только по словам (без учёта смысла и перефразировок).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Индекс: фрагментов ${stats.chunks}, с векторами ${stats.embedded}, дней ${stats.days}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.running) Text("Идёт: ${state.step}", style = MaterialTheme.typography.bodySmall)
            state.last?.let { Text("Последний запуск: $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenChat) { Text("Спросить") }
                OutlinedButton(onClick = vm::indexNow, enabled = !state.running) { Text("Индексировать сейчас") }
            }
        }
    }
}
