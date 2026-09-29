package app.basis.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.basis.audio.capture.RecorderStatus
import app.basis.audio.capture.RecordingController
import app.basis.core.datastore.RecordingPrefs
import app.basis.core.datastore.RecordingSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: RecordingController,
    private val prefs: RecordingPrefs,
) : ViewModel() {
    val status: StateFlow<RecorderStatus> = controller.status
    val settings: StateFlow<RecordingSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingSettings())

    fun start() = viewModelScope.launch { controller.start() }
    fun pause() = viewModelScope.launch { controller.pause() }
    fun resume() = viewModelScope.launch { controller.resume() }
    fun stop() = viewModelScope.launch { controller.stop() }
    fun privateFor(minutes: Int) = viewModelScope.launch { controller.privateFor(minutes) }
    fun setHoldWakeLock(hold: Boolean) = viewModelScope.launch { prefs.setHoldWakeLock(hold) }

    /** Recording is wanted but the service isn't alive (killed, rebooted): restart from the foreground UI. */
    fun ensureRunningIfWanted() = viewModelScope.launch {
        val s = prefs.current()
        if (s.enabled && status.value.mode == app.basis.audio.capture.RecorderMode.STOPPED) {
            controller.ensureServiceRunning("открыт главный экран")
        }
    }
}
