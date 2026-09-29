package app.basis.feature.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.basis.core.common.formatDuration
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelState

@Composable
fun SpeechCards(vm: SpeechViewModel = hiltViewModel()) {
    val live by vm.live.collectAsStateWithLifecycle()
    val vad by vm.vadModel.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Речь", style = MaterialTheme.typography.titleMedium)
            when {
                !live.running -> Text("VAD не работает (запись выключена)")
                !live.vadReady -> Text("VAD ждёт модель — речь не сохраняется", color = MaterialTheme.colorScheme.error)
                else -> {
                    Text(if (live.speaking) "● Говорят" else "○ Тишина")
                    LinearProgressIndicator(progress = { live.probability }, modifier = Modifier.fillMaxWidth())
                    Text("VAD: ${live.lastVadMicros} мкс на окно 32 мс", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Речь за сессию: ${formatDuration(live.sessionSpeechMs / 1000)}, сегментов ${live.sessionSegments}")
            live.hour?.let {
                Text("В этом часе: речь ${formatDuration(it.speechMs / 1000)} из ${formatDuration(it.audioMs / 1000)} (%.1f%%)".format(it.speechShare * 100))
            }
            if (live.droppedFrames > 0) Text("Потеряно кадров: ${live.droppedFrames}", color = MaterialTheme.colorScheme.error)
            Text(
                "Зашифрованный буфер: ${live.buffer.count} сегм., ${formatDuration(live.buffer.audioMs / 1000)}, " +
                    "%.1f МБ".format(live.buffer.bytes / 1024.0 / 1024.0),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.playLast() }, enabled = live.buffer.count > 0) { Text("Прослушать последний") }
                TextButton(onClick = { vm.clearBuffer() }, enabled = live.buffer.count > 0) { Text("Очистить буфер") }
            }
            Text(
                "Прослушивание расшифровывает сегмент только в памяти — чтобы проверить, как VAD режет речь.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Модели", style = MaterialTheme.typography.titleMedium)
            val spec = ModelCatalog.SILERO_VAD
            Text("${spec.title} (${spec.totalBytes / 1024} КБ, ${spec.license})")
            when (val s = vad) {
                ModelState.Ready -> Text("Готова ✓")
                ModelState.Missing -> Text("Не загружена")
                is ModelState.Downloading -> {
                    Text("Загрузка ${s.bytes / 1024} / ${s.total / 1024} КБ")
                    LinearProgressIndicator(progress = { if (s.total > 0) s.bytes.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
                }
                is ModelState.Failed -> Text("Ошибка: ${s.message}", color = MaterialTheme.colorScheme.error)
            }
            if (vad != ModelState.Ready && vad !is ModelState.Downloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::downloadVad) { Text("Скачать") }
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Импорт файла") }
                }
            }
            Text("Источник: ${spec.source}. Файл проверяется по SHA-256.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
