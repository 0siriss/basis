package app.basis.audio.capture

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.basis.core.common.AppLog
import app.basis.core.datastore.RecordingPrefs
import app.basis.audio.vad.SpeechPipeline
import app.basis.core.datastore.RecordingSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (type microphone) that owns the AudioRecord. It observes the desired state in
 * [RecordingPrefs] and applies it: record / pause / private mode / stop.
 *
 * Android 14+: a microphone FGS can't be started from the background (boot, sticky restart after the
 * process was killed). In that case we post a "tap to resume" notification instead of recording silence.
 */
@AndroidEntryPoint
class RecordingService : LifecycleService() {

    @Inject lateinit var prefs: RecordingPrefs
    @Inject lateinit var controller: RecordingController
    @Inject lateinit var speech: SpeechPipeline

    private var capture: AudioCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null
    private var inForeground = false
    private var shownMode: RecorderMode? = null
    @Volatile private var status = RecorderStatus()
    private var silentSinceMs = 0L
    private var silenceReported = false

    /** Frames go to VAD; only detected speech is kept (encrypted). */
    private val sink = AudioFrameSink { samples, count, timeMs -> speech.onFrame(samples, count, timeMs) }

    override fun onCreate() {
        super.onCreate()
        RecordingNotifications.ensureChannels(this)
        AppLog.i(TAG, "сервис создан (pid=${android.os.Process.myPid()})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (intent == null) {
            AppLog.w(TAG, "сервис перезапущен системой после остановки процесса (START_STICKY)")
        } else {
            AppLog.d(TAG, "команда: $action")
        }

        if (!inForeground && !enterForeground(restartedBySystem = intent == null)) {
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PAUSE -> lifecycleScope.launch { prefs.setPaused(true) }
            ACTION_RESUME -> lifecycleScope.launch { prefs.setPaused(false) }
            ACTION_PRIVATE_15 -> lifecycleScope.launch { controller.privateFor(15) }
            ACTION_STOP -> lifecycleScope.launch { prefs.setEnabled(false) }
        }

        if (observeJob == null) {
            observeJob = lifecycleScope.launch {
                prefs.settings.distinctUntilChanged().collectLatest { apply(it) }
            }
        }
        return START_STICKY
    }

    private fun enterForeground(restartedBySystem: Boolean): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.e(TAG, "нет разрешения на микрофон — сервис не может стартовать")
            publish(RecorderStatus(mode = RecorderMode.BLOCKED, problem = "Нет разрешения на микрофон"))
            stopSelf()
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this, RecordingNotifications.ID_ONGOING,
                RecordingNotifications.ongoing(this, RecorderMode.STOPPED, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            inForeground = true
            status = status.copy(serviceStartedAtMs = System.currentTimeMillis())
            RecordingNotifications.cancelResumeRequest(this)
            AppLog.i(TAG, "startForeground(microphone) OK${if (restartedBySystem) " после перезапуска системой" else ""}")
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException / SecurityException on Android 12/14+ from background.
            AppLog.e(TAG, "startForeground(microphone) запрещён: ${t.javaClass.simpleName}: ${t.message}")
            RecordingNotifications.showResumeRequest(this, "Android не разрешает включить микрофон в фоне")
            publish(RecorderStatus(mode = RecorderMode.BLOCKED, problem = "Нужно открыть приложение, чтобы продолжить запись"))
            stopSelf()
            false
        }
    }

    private suspend fun apply(s: RecordingSettings) {
        val now = System.currentTimeMillis()
        when {
            !s.enabled -> {
                stopCapture()
                AppLog.i(TAG, "запись выключена пользователем — остановка сервиса")
                publish(status.copy(mode = RecorderMode.STOPPED, live = null, problem = null))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                inForeground = false
                stopSelf()
            }
            s.privateUntilMs > now -> {
                stopCapture()
                show(RecorderMode.PRIVATE, s.privateUntilMs)
                delay(s.privateUntilMs - now)
                AppLog.i(TAG, "приватный режим закончился")
                prefs.setPrivateUntil(0)
            }
            s.paused -> {
                stopCapture()
                show(RecorderMode.PAUSED, 0)
            }
            else -> {
                if (startCapture(s.holdWakeLock)) {
                    show(RecorderMode.RECORDING, 0)
                } else {
                    publish(status.copy(mode = RecorderMode.BLOCKED, problem = "Не удалось открыть микрофон (см. логи)"))
                }
            }
        }
    }

    private fun startCapture(holdWakeLock: Boolean): Boolean {
        if (capture?.isRunning == true) return true
        silentSinceMs = 0
        silenceReported = false
        val c = AudioCapture(
            context = this,
            sink = sink,
            onLive = ::onLive,
            onUnexpectedEnd = {
                AppLog.e(TAG, "запись прервалась — пробую перезапустить через 2 с")
                lifecycleScope.launch {
                    delay(2000)
                    capture = null
                    val s = prefs.current()
                    if (s.enabled && !s.paused && s.privateUntilMs <= System.currentTimeMillis()) startCapture(s.holdWakeLock)
                }
            },
        )
        speech.start()
        if (!c.start()) {
            speech.stop()
            return false
        }
        capture = c
        if (holdWakeLock) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "basis:recording")
                .apply { acquire() }
            AppLog.i(TAG, "удерживается partial wake lock")
        }
        return true
    }

    private fun stopCapture() {
        capture?.stop()
        capture = null
        speech.stop() // flushes the segment in progress
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        status = status.copy(live = null)
    }

    private fun onLive(live: CaptureLive) {
        // Continuous digital silence means Android is feeding us zeros (mic not really available).
        val now = System.currentTimeMillis()
        val silent = live.silencedByOs || live.levelDbfs <= CaptureStats.SILENCE_DBFS
        if (!silent) {
            silentSinceMs = 0
            silenceReported = false
        } else if (silentSinceMs == 0L) {
            silentSinceMs = now
        } else if (!silenceReported && now - silentSinceMs > 10_000) {
            silenceReported = true
            AppLog.w(TAG, "10 с цифровой тишины — микрофон фактически недоступен")
            RecordingNotifications.showResumeRequest(this, "Микрофон недоступен (система отдаёт тишину)")
        }
        // Called on the capture thread: don't touch [status], only push the merged value to the UI.
        controller.publish(status.copy(live = live))
    }

    private fun show(mode: RecorderMode, privateUntilMs: Long) {
        publish(status.copy(mode = mode, privateUntilMs = privateUntilMs, problem = null))
        if (shownMode == mode && mode != RecorderMode.PRIVATE) return
        shownMode = mode
        AppLog.i(TAG, "режим: $mode")
        getSystemService(NotificationManager::class.java)
            .notify(RecordingNotifications.ID_ONGOING, RecordingNotifications.ongoing(this, mode, privateUntilMs))
    }

    private fun publish(s: RecorderStatus) {
        status = s
        controller.publish(s)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLog.i(TAG, "приложение смахнуто из недавних — сервис продолжает работу")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.d(TAG, "onTrimMemory($level)")
    }

    override fun onDestroy() {
        stopCapture()
        publish(status.copy(mode = if (status.mode == RecorderMode.BLOCKED) RecorderMode.BLOCKED else RecorderMode.STOPPED, live = null))
        AppLog.i(TAG, "сервис уничтожен")
        super.onDestroy()
    }

    companion object {
        const val ACTION_SYNC = "app.basis.recording.SYNC"
        const val ACTION_PAUSE = "app.basis.recording.PAUSE"
        const val ACTION_RESUME = "app.basis.recording.RESUME"
        const val ACTION_PRIVATE_15 = "app.basis.recording.PRIVATE_15"
        const val ACTION_STOP = "app.basis.recording.STOP"
        private const val TAG = "Service"
    }
}
