package app.basis.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.basis.ml.models.ModelSpec
import app.basis.ml.models.ModelState

private fun mb(bytes: Long) = "%.0f МБ".format(bytes / 1024.0 / 1024.0)

/** Status + download / import / delete for one model. */
@Composable
fun ModelRow(
    spec: ModelSpec,
    state: ModelState,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Загрузка ${mb(spec.downloadBytes)}, на диске ${mb(spec.installedBytes)} · ${spec.license}",
            style = MaterialTheme.typography.bodySmall,
        )
        when (state) {
            ModelState.Ready -> Text("Готова ✓", style = MaterialTheme.typography.bodySmall)
            ModelState.Missing -> Text("Не загружена", style = MaterialTheme.typography.bodySmall)
            is ModelState.Downloading -> {
                Text("Загрузка ${mb(state.bytes)} / ${mb(state.total)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (state.total > 0) state.bytes.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
            }
            is ModelState.Extracting -> {
                Text("Распаковка ${mb(state.bytes)} / ~${mb(state.total)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (state.total > 0) (state.bytes.toFloat() / state.total).coerceAtMost(1f) else 0f }, modifier = Modifier.fillMaxWidth())
            }
            is ModelState.Failed -> Text("Ошибка: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        val busy = state is ModelState.Downloading || state is ModelState.Extracting
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state != ModelState.Ready && !busy) {
                OutlinedButton(onClick = onDownload) { Text("Скачать") }
                TextButton(onClick = onImport) { Text("Импорт файла") }
            }
            if (state == ModelState.Ready && onDelete != null) TextButton(onClick = onDelete) { Text("Удалить") }
        }
    }
}
