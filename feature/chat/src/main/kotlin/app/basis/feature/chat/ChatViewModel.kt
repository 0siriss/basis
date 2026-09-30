package app.basis.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.basis.pipeline.search.ChatEvent
import app.basis.pipeline.search.ChatPrompts
import app.basis.pipeline.search.ChatSource
import app.basis.pipeline.search.DiaryChat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val status: String? = null,
    val sources: List<ChatSource> = emptyList(),
    val stats: String? = null,
    val error: String? = null,
) {
    /** Sources the answer cites; all of them if it cites none (so the user can still check). */
    fun citedSources(): List<ChatSource> {
        if (sources.isEmpty() || status != null) return emptyList()
        val cited = ChatPrompts.citations(text)
        return sources.filter { it.n in cited }.ifEmpty { sources }
    }
}

@HiltViewModel
class ChatViewModel @Inject constructor(private val chat: DiaryChat) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var job: Job? = null
    private var nextId = 0L

    fun ask(question: String) {
        if (_busy.value) return
        val answerId = nextId + 1
        _messages.value = _messages.value + ChatMessage(nextId, true, question) + ChatMessage(answerId, false, "", status = "…")
        nextId += 2
        _busy.value = true
        job = viewModelScope.launch {
            try {
                chat.ask(question).collect { e ->
                    update(answerId) { m ->
                        when (e) {
                            is ChatEvent.Status -> m.copy(status = e.text)
                            is ChatEvent.Sources -> m.copy(sources = e.sources)
                            is ChatEvent.Token -> m.copy(text = m.text + e.text, status = null)
                            is ChatEvent.Done -> m.copy(stats = e.stats, status = null, text = m.text.trim())
                            is ChatEvent.Error -> m.copy(error = e.message, status = null)
                        }
                    }
                }
            } finally {
                update(answerId) { if (it.status != null) it.copy(status = null, error = it.error ?: "Остановлено") else it }
                _busy.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun update(id: Long, f: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) f(it) else it }
    }

    override fun onCleared() {
        chat.release()
    }
}
