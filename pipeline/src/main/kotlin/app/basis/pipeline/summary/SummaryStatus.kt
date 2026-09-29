package app.basis.pipeline.summary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SummaryState(
    val running: Boolean = false,
    val step: String = "",
    val last: String? = null,
)

@Singleton
class SummaryStatus @Inject constructor() {
    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state.asStateFlow()
    fun update(f: (SummaryState) -> SummaryState) { _state.value = f(_state.value) }
}
