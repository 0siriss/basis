package app.basis.feature.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.basis.audio.capture.RecorderMode
import app.basis.core.common.formatDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenLogs: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var permsTick by remember { mutableIntStateOf(0) }
    val micGranted = remember(permsTick) {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(permsTick) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryExempt = remember(permsTick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    var pendingStart by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsTick++
        if (pendingStart && it[Manifest.permission.RECORD_AUDIO] == true) vm.start()
        pendingStart = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permsTick++
        vm.ensureRunningIfWanted()
    }

    fun requestPermsThenStart() {
        if (micGranted) vm.start() else {
            pendingStart = true
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ежедневник") },
                actions = {
                    IconButton(onClick = onOpenLogs) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Логи") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val mode = status.mode
            val title = when {
                mode == RecorderMode.RECORDING -> "Идёт запись"
                mode == RecorderMode.PAUSED -> "Пауза"
                mode == RecorderMode.PRIVATE ->
                    "Приватный режим до ${HM.format(Instant.ofEpochMilli(status.privateUntilMs).atZone(ZoneId.systemDefault()))}"
                mode == RecorderMode.BLOCKED -> "Запись невозможна"
                settings.enabled -> "Сервис не запущен"
                else -> "Запись выключена"
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val live = status.live
            if (mode == RecorderMode.RECORDING && live != null) {
                val level = ((live.levelDbfs + 80.0) / 80.0).coerceIn(0.0, 1.0).toFloat()
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                Text("Уровень: %.0f dBFS%s".format(live.levelDbfs, if (live.silencedByOs) " · заглушено системой" else ""))
            }

            val recording = mode == RecorderMode.RECORDING
            Button(
                onClick = {
                    when (mode) {
                        RecorderMode.RECORDING -> vm.pause()
                        RecorderMode.PAUSED, RecorderMode.PRIVATE -> vm.resume()
                        else -> requestPermsThenStart()
                    }
                },
                shape = CircleShape,
                modifier = Modifier.size(160.dp),
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (recording) Icons.Filled.Pause else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(48.dp))
                    Text(
                        when (mode) {
                            RecorderMode.RECORDING -> "Пауза"
                            RecorderMode.PAUSED, RecorderMode.PRIVATE -> "Продолжить"
                            else -> "Старт"
                        },
                    )
                }
            }

            Text("Приватный режим (без записи):")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 30, 60).forEach { m ->
                    OutlinedButton(onClick = { vm.privateFor(m) }, enabled = settings.enabled) { Text("$m мин") }
                }
            }
            if (settings.enabled) TextButton(onClick = vm::stop) { Text("Выключить запись полностью") }

            SpeechCards()

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Диагностика", style = MaterialTheme.typography.titleMedium)
                    if (live != null) {
                        Text("Аудио за сессию: ${formatDuration(live.totalAudioMs / 1000)}")
                        Text("Из них при выключенном экране: ${formatDuration(live.screenOffAudioMs / 1000)}")
                        live.lastWindow?.let {
                            Text("Последняя минута: покрытие %.1f%%, заглушено %d с".format(it.coverage * 100, it.silencedAudioMs / 1000))
                        }
                    }
                    Text("Микрофон: ${if (micGranted) "разрешён" else "нет разрешения"}")
                    Text("Уведомления: ${if (notifGranted) "разрешены" else "нет разрешения"}")
                    Text("Оптимизация батареи: ${if (batteryExempt) "отключена ✓" else "включена — система может убить сервис"}")
                    if (!batteryExempt) {
                        TextButton(onClick = { context.startActivity(batteryIntent(context.packageName)) }) {
                            Text("Отключить оптимизацию батареи")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Держать CPU (wake lock)", Modifier.weight(1f))
                        Switch(checked = settings.holdWakeLock, onCheckedChange = vm::setHoldWakeLock)
                    }
                    Text(
                        "Включайте, только если в логах покрытие < 95% при выключенном экране. Применяется при следующем старте записи.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@SuppressLint("BatteryLife")
private fun batteryIntent(pkg: String) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
