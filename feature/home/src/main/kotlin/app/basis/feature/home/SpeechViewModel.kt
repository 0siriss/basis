package app.basis.feature.home

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.basis.audio.buffer.SegmentStore
import app.basis.audio.vad.SpeechLive
import app.basis.audio.vad.SpeechPipeline
import app.basis.core.common.AppLog
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import app.basis.ml.models.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpeechViewModel @Inject constructor(
    private val pipeline: SpeechPipeline,
    private val models: ModelManager,
    private val store: SegmentStore,
) : ViewModel() {
    val live: StateFlow<SpeechLive> = pipeline.live
    val vadModel: StateFlow<ModelState> = models.state(ModelCatalog.SILERO_VAD)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { pipeline.refreshBufferStats() }
    }

    fun downloadVad() = models.download(ModelCatalog.SILERO_VAD)

    fun import(uri: Uri) = viewModelScope.launch { _message.value = models.import(uri) }

    fun consumeMessage() {
        _message.value = null
    }

    /** Decrypts the newest segment in memory and plays it once — to check how VAD cuts speech. */
    fun playLast() = viewModelScope.launch(Dispatchers.IO) {
        val seg = store.list().lastOrNull() ?: run { _message.value = "Буфер пуст"; return@launch }
        val pcm = try {
            store.read(seg)
        } catch (t: Throwable) {
            AppLog.e("Home", "не удалось расшифровать сегмент", t)
            _message.value = "Не удалось расшифровать сегмент"
            return@launch
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(seg.meta.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(seg.meta.durationMs + 200)
        } finally {
            track.release()
            pcm.fill(0)
        }
    }

    fun clearBuffer() = viewModelScope.launch(Dispatchers.IO) {
        val n = store.clear()
        AppLog.i("Home", "буфер очищен вручную: удалено файлов $n")
        pipeline.refreshBufferStats()
        _message.value = "Удалено сегментов: $n"
    }
}
