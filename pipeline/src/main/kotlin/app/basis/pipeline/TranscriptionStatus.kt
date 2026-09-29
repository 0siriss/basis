package app.basis.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

data class TranscriptionState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val modelTitle: String = "",
    val last: String? = null,
)

/** Live status for the UI plus a process-wide lock so two workers never transcribe the same segments. */
@Singleton
class TranscriptionStatus @Inject constructor() {
    val lock = Mutex()
    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    fun update(f: (TranscriptionState) -> TranscriptionState) {
        _state.value = f(_state.value)
    }
}
