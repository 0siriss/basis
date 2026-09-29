package app.basis.audio.capture

import android.content.Context
import android.content.Intent
import app.basis.core.common.AppLog
import app.basis.core.datastore.RecordingPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class RecorderMode { STOPPED, RECORDING, PAUSED, PRIVATE, BLOCKED }

data class RecorderStatus(
    val mode: RecorderMode = RecorderMode.STOPPED,
    val privateUntilMs: Long = 0,
    /** Why recording can't run (BLOCKED), human-readable. */
    val problem: String? = null,
    val live: CaptureLive? = null,
    val serviceStartedAtMs: Long = 0,
)

/**
 * Single entry point for UI, notification, tile and receivers. Commands change the persisted
 * desired state in [RecordingPrefs]; [RecordingService] observes it and applies it.
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: RecordingPrefs,
) {
    private val _status = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = _status.asStateFlow()

    internal fun publish(status: RecorderStatus) {
        _status.value = status
    }

    /** Must be called from a foreground context (visible activity, tile click, notification tap). */
    suspend fun start() {
        prefs.setEnabled(true)
        prefs.setPaused(false)
        ensureServiceRunning("start")
    }

    suspend fun pause() = prefs.setPaused(true)

    suspend fun resume() {
        prefs.setPaused(false)
        ensureServiceRunning("resume")
    }

    suspend fun privateFor(minutes: Int) {
        prefs.setPrivateUntil(System.currentTimeMillis() + minutes * 60_000L)
        AppLog.i(TAG, "приватный режим на $minutes мин")
    }

    suspend fun stop() = prefs.setEnabled(false)

    fun ensureServiceRunning(reason: String): Boolean = try {
        context.startForegroundService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_SYNC))
        true
    } catch (t: Throwable) {
        // ForegroundServiceStartNotAllowedException when called from the background (Android 12+).
        AppLog.e(TAG, "не удалось запустить сервис записи ($reason)", t)
        _status.value = _status.value.copy(mode = RecorderMode.BLOCKED, problem = "Система не разрешила запуск из фона — откройте приложение")
        false
    }

    private companion object {
        const val TAG = "Controller"
    }
}
