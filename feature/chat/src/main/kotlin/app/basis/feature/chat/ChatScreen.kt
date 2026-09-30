package app.basis.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.basis.pipeline.search.ChatSource

private val EXAMPLES = listOf(
    "Что я обещал сделать вчера?",
    "О чём договорились на этой неделе?",
    "Какие идеи были за последние дни?",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(onBack: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var shown by remember { mutableStateOf<ChatSource?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1)
    }
    fun send() {
        val q = input.trim()
        if (q.isNotEmpty() && !busy) {
            vm.ask(q)
            input = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Вопросы к дневнику") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Что я обещал Саше во вторник?") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    if (busy) {
                        IconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, contentDescription = "Остановить") }
                    } else {
                        IconButton(onClick = { send() }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Спросить") }
                    }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Ответы строятся только из вашего дневника: сначала поиск по словам и по смыслу, затем локальная модель " +
                        "пишет ответ со ссылками на фрагменты. Всё на телефоне, без сети.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Например:", style = MaterialTheme.typography.labelLarge)
                EXAMPLES.forEach { q -> TextButton(onClick = { input = q }) { Text(q) } }
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    if (m.fromUser) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Text(m.text, Modifier.padding(12.dp))
                            }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (m.text.isNotEmpty()) Text(m.text)
                                m.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                m.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                val cited = m.citedSources()
                                if (cited.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        cited.forEach { s ->
                                            AssistChip(onClick = { shown = s }, label = { Text("[${s.n}] ${shortLabel(s)}") })
                                        }
                                    }
                                }
                                m.stats?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }

    shown?.let { s ->
        AlertDialog(
            onDismissRequest = { shown = null },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Закрыть") } },
            title = { Text("[${s.n}] ${s.label}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(s.chunk.text) } },
        )
    }
}

/** "вторник, 29 сентября 2026, 15:00–15:10, расшифровка" → "29 сентября, 15:00". */
private fun shortLabel(s: ChatSource): String {
    val parts = s.label.split(", ")
    val date = parts.getOrNull(1) ?: s.label
    val rest = parts.getOrNull(3)?.substringBefore('–') ?: parts.getOrNull(3) ?: ""
    return listOf(date.substringBeforeLast(' '), rest).filter { it.isNotEmpty() }.joinToString(", ")
}
