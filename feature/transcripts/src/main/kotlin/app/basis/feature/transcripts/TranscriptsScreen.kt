package app.basis.feature.transcripts

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.basis.core.database.DaySummaryRow
import app.basis.core.database.TranscriptDao
import app.basis.core.database.TranscriptEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TranscriptsViewModel @Inject constructor(private val dao: TranscriptDao) : ViewModel() {
    val days: StateFlow<List<DaySummaryRow>> = dao.observeDays().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val openDay = MutableStateFlow<String?>(null)
    val items: StateFlow<List<TranscriptEntity>> = openDay
        .flatMapLatest { d -> if (d == null) flowOf(emptyList()) else dao.observeDay(d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(day: String) {
        openDay.value = if (openDay.value == day) null else day
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))

private fun time(ms: Long) = HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Temporary raw view (stage 3) to judge ASR quality; stage 4 turns it into the day timeline with summaries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptsScreen(onBack: () -> Unit, vm: TranscriptsViewModel = hiltViewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val open by vm.openDay.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Расшифровки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        if (days.isEmpty()) {
            Text("Пока пусто. Расшифровки появятся после распознавания (на зарядке или кнопкой «Распознать сейчас»).", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days, key = { it.day }) { d ->
                Card(Modifier.fillMaxWidth().clickable { vm.toggle(d.day) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(DAY.format(LocalDate.parse(d.day)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${d.segments} фрагм., речь ${d.speechMs / 60_000} мин, ${time(d.firstMs).take(5)}–${time(d.lastMs).take(5)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (open == d.day) {
                            items.forEach { t ->
                                Row(Modifier.padding(top = 8.dp)) {
                                    Text(time(t.startMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
                                    Text(t.text + if (t.lang.isNotEmpty()) " [${t.lang}]" else "", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
