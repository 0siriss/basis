package app.basis.feature.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.basis.core.datastore.AsrPrefs
import app.basis.core.datastore.AsrSettings
import app.basis.core.datastore.ProcessingMode
import app.basis.ml.asr.AsrModel
import app.basis.ml.models.ModelManager
import app.basis.ml.models.ModelState
import app.basis.pipeline.TranscriptionScheduler
import app.basis.pipeline.TranscriptionState
import app.basis.pipeline.TranscriptionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AsrViewModel @Inject constructor(
    private val prefs: AsrPrefs,
    private val models: ModelManager,
    private val scheduler: TranscriptionScheduler,
    status: TranscriptionStatus,
) : ViewModel() {
    val settings: StateFlow<AsrSettings> = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AsrSettings())
    val state: StateFlow<TranscriptionState> = status.state
    val modelStates: Map<AsrModel, StateFlow<ModelState>> = AsrModel.entries.associateWith { models.state(it.spec) }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun select(m: AsrModel) = viewModelScope.launch { prefs.setModel(m.spec.id) }
    fun download(m: AsrModel) = models.download(m.spec)
    fun delete(m: AsrModel) = models.delete(m.spec)
    fun import(uri: Uri) = viewModelScope.launch { _message.value = models.import(uri) }
    fun consumeMessage() { _message.value = null }

    fun setMode(mode: ProcessingMode) = viewModelScope.launch { prefs.setMode(mode); scheduler.scheduleFromSettings() }
    fun setThreshold(pct: Int) = viewModelScope.launch { prefs.setBatteryThreshold(pct); scheduler.scheduleFromSettings() }
    fun setThreads(n: Int) = viewModelScope.launch { prefs.setThreads(n) }
    fun setWhisperLanguage(lang: String) = viewModelScope.launch { prefs.setWhisperLanguage(lang) }
    fun runNow() = scheduler.runNow()
}
