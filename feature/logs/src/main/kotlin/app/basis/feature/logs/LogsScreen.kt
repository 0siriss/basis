package app.basis.feature.logs

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.basis.core.common.AppLog
import app.basis.core.common.LogLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    var follow by remember { mutableStateOf(true) }
    val shown = remember(entries, minLevel) { entries.filter { it.level >= minLevel } }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Логи") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Basis logs")
                            .putExtra(Intent.EXTRA_TEXT, AppLog.exportText().takeLast(200_000))
                        context.startActivity(Intent.createChooser(send, "Отправить логи"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Поделиться") }
                    IconButton(onClick = AppLog::clear) { Icon(Icons.Filled.Delete, contentDescription = "Очистить экран") }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                LogLevel.entries.forEach { lvl ->
                    FilterChip(selected = minLevel == lvl, onClick = { minLevel = lvl }, label = { Text("≥ ${lvl.name}") })
                }
                FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Автопрокрутка") })
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(shown, key = { it.seq }) { e ->
                    Text(
                        text = if (e.seq < -1) e.message else e.format(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = when (e.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> Color(0xFFE08A00)
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
