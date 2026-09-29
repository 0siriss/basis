package app.basis.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.basis.core.database.DaySummaryRow
import app.basis.core.database.SummaryDao
import app.basis.core.database.SummaryEntity
import app.basis.core.database.TranscriptDao
import app.basis.core.database.TranscriptEntity
import app.basis.pipeline.TranscriptionScheduler
import app.basis.pipeline.summary.Digest
import app.basis.pipeline.summary.SummaryState
import app.basis.pipeline.summary.SummaryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DayItem(val row: DaySummaryRow, val digest: Digest?)

data class OpenDay(
    val day: String,
    val transcripts: List<TranscriptEntity> = emptyList(),
    val hourDigests: Map<Long, Digest> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    transcripts: TranscriptDao,
    summaries: SummaryDao,
    private val scheduler: TranscriptionScheduler,
    status: SummaryStatus,
) : ViewModel() {
    val days: StateFlow<List<DayItem>> = combine(transcripts.observeDays(), summaries.observeDaySummaries()) { rows, sums ->
        val byDay = sums.associate { it.day to runCatching { Digest.parse(it.json) }.getOrNull() }
        rows.map { DayItem(it, byDay[it.day]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openDayId = MutableStateFlow<String?>(null)
    val open: StateFlow<OpenDay?> = openDayId.flatMapLatest { d ->
        if (d == null) flowOf(null)
        else combine(transcripts.observeDay(d), summaries.observeDay(d)) { ts, sums ->
            OpenDay(
                d, ts,
                sums.filter { it.kind == SummaryEntity.HOUR }
                    .mapNotNull { s -> runCatching { Digest.parse(s.json) }.getOrNull()?.let { s.periodStartMs to it } }
                    .toMap(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val summaryState: StateFlow<SummaryState> = status.state

    fun toggle(day: String) {
        openDayId.value = if (openDayId.value == day) null else day
    }

    fun summarizeNow() = scheduler.summarizeNow()
}
