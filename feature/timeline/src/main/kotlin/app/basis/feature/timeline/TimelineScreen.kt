package app.basis.feature.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.basis.pipeline.summary.Digest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))
private fun hm(ms: Long) = HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(onBack: () -> Unit, vm: TimelineViewModel = hiltViewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val state by vm.summaryState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Дневник") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
                actions = {
                    IconButton(onClick = vm::summarizeNow, enabled = !state.running) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Сводка сейчас")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.running) {
                Text("Сводка: ${state.step}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            } else {
                state.last?.let { Text("Последняя сводка: $it", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            if (days.isEmpty()) {
                Text(
                    "Пока пусто. Записи появятся после распознавания, сводки — после обработки на зарядке или по кнопке ✨.",
                    Modifier.padding(16.dp),
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(days, key = { it.row.day }) { item ->
                    DayCard(item, open?.takeIf { it.day == item.row.day }, onToggle = { vm.toggle(item.row.day) })
                }
            }
        }
    }
}

@Composable
private fun DayCard(item: DayItem, open: OpenDay?, onToggle: () -> Unit) {
    val d = item.row
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
                Column(Modifier.weight(1f)) {
                    Text(DAY.format(LocalDate.parse(d.day)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${d.segments} фрагм., речь ${d.speechMs / 60_000} мин, ${hm(d.firstMs)}–${hm(d.lastMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(if (open != null) "▲" else "▼")
            }
            if (item.digest != null) DigestView(item.digest, full = true)
            else Text("Итог дня появится после окончания дня (на зарядке) или по кнопке ✨", style = MaterialTheme.typography.bodySmall)

            if (open != null) {
                HorizontalDivider()
                val zone = ZoneId.systemDefault()
                open.transcripts.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli() }
                    .toSortedMap()
                    .forEach { (hourStart, ts) -> HourBlock(hourStart, ts.map { hm(it.startMs) to it.text }, open.hourDigests[hourStart]) }
            }
        }
    }
}

@Composable
private fun HourBlock(hourStart: Long, lines: List<Pair<String, String>>, digest: Digest?) {
    var showText by rememberSaveable(hourStart) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${hm(hourStart)}–${hm(hourStart + 3_600_000)} · ${lines.size} фрагм.", style = MaterialTheme.typography.titleSmall)
            if (digest != null) DigestView(digest, full = false) else Text("Сводка часа ещё не готова", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showText = !showText }) { Text(if (showText) "Скрыть расшифровку" else "Расшифровка") }
            if (showText) {
                lines.forEach { (time, text) ->
                    Row {
                        Text(time, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun DigestView(d: Digest, full: Boolean) {
    if (d.isEmpty) {
        Text("Ничего существенного", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(d.summary, style = MaterialTheme.typography.bodyMedium)
    if (d.mood.isNotBlank()) AssistChip(onClick = {}, label = { Text("Настроение: ${d.mood}") })
    Section("Договорённости и обещания", d.agreements)
    Section("Задачи", d.tasks)
    Section("События и встречи", d.events)
    if (full) Section("Идеи", d.ideas)
}

@Composable
private fun Section(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}
