package app.basis.feature.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.basis.core.datastore.ProcessingMode
import app.basis.ml.asr.AsrModel
import app.basis.ml.models.ModelState

@Composable
fun AsrCard(vm: AsrViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeMessage() }
    }
    val selected = AsrModel.byId(settings.modelId)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Распознавание речи", style = MaterialTheme.typography.titleMedium)

            AsrModel.entries.forEach { m ->
                val ms by vm.modelStates.getValue(m).collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m == selected, onClick = { vm.select(m) })
                    Text(m.spec.title, style = MaterialTheme.typography.bodyLarge)
                }
                if (m == selected || ms != ModelState.Missing) {
                    Column(Modifier.padding(start = 48.dp)) {
                        ModelRow(m.spec, ms, onDownload = { vm.download(m) }, onImport = { picker.launch(arrayOf("*/*")) }, onDelete = { vm.delete(m) })
                    }
                }
            }
            if (selected.multilingual) {
                Text("Язык Whisper:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Авто (ru+en)", "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                        FilterChip(selected = settings.whisperLanguage == code, onClick = { vm.setWhisperLanguage(code) }, label = { Text(label) })
                    }
                }
            }
            Text("Потоков CPU: ${settings.threads}")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 6, 8).forEach { n -> FilterChip(selected = settings.threads == n, onClick = { vm.setThreads(n) }, label = { Text("$n") }) }
            }

            HorizontalDivider()
            Text("Когда распознавать")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.mode == ProcessingMode.CHARGING_ONLY, onClick = { vm.setMode(ProcessingMode.CHARGING_ONLY) })
                Text("Только на зарядке")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.mode == ProcessingMode.CHARGING_OR_BATTERY_ABOVE,
                    onClick = { vm.setMode(ProcessingMode.CHARGING_OR_BATTERY_ABOVE) },
                )
                Text("На зарядке или сразу, если батарея ≥ ${settings.batteryThreshold}%")
            }
            if (settings.mode == ProcessingMode.CHARGING_OR_BATTERY_ABOVE) {
                var slider by remember(settings.batteryThreshold) { mutableFloatStateOf(settings.batteryThreshold.toFloat()) }
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { vm.setThreshold(slider.toInt()) },
                    valueRange = 20f..100f,
                    steps = 15,
                )
            }
            Text("Проверка каждые 15 минут. Аудиофайл удаляется сразу после распознавания.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            if (state.running) {
                Text("Идёт распознавание (${state.modelTitle}): ${state.done} / ${state.total}")
                LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
            } else {
                state.last?.let { Text("Последний запуск: $it", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::runNow) { Text("Распознать сейчас") }
                Text("Запуск без условий (для проверки и замера скорости). Подробности — в логах, тег ASR.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
