package app.basis.audio.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object RecordingNotifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ALERTS = "recording_alerts"
    const val ID_ONGOING = 1
    const val ID_RESUME = 2

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Запись", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока идёт запись"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Запись остановлена", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Просьба продолжить запись после перезагрузки или остановки системой"
            },
        )
    }

    fun ongoing(context: Context, mode: RecorderMode, privateUntilMs: Long): Notification {
        val (title, text) = when (mode) {
            RecorderMode.RECORDING -> "Идёт запись" to "Аудио не сохраняется — только текст"
            RecorderMode.PAUSED -> "Запись на паузе" to "Нажмите «Продолжить», чтобы возобновить"
            RecorderMode.PRIVATE -> "Приватный режим" to "Без записи до ${HM.format(Instant.ofEpochMilli(privateUntilMs).atZone(ZoneId.systemDefault()))}"
            else -> "Ежедневник" to "Подготовка…"
        }
        val b = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(if (mode == RecorderMode.RECORDING) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_media_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
        when (mode) {
            RecorderMode.RECORDING -> {
                b.addAction(0, "Пауза", serviceAction(context, RecordingService.ACTION_PAUSE, 1))
                b.addAction(0, "Приват 15 мин", serviceAction(context, RecordingService.ACTION_PRIVATE_15, 2))
            }
            RecorderMode.PAUSED, RecorderMode.PRIVATE ->
                b.addAction(0, "Продолжить", serviceAction(context, RecordingService.ACTION_RESUME, 3))
            else -> Unit
        }
        b.addAction(0, "Стоп", serviceAction(context, RecordingService.ACTION_STOP, 4))
        return b.build()
    }

    /** Shown when recording should run but Android doesn't allow us to start the mic from the background. */
    fun showResumeRequest(context: Context, reason: String) {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 10,
            Intent(context, ResumeRecordingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Запись остановлена")
            .setContentText("$reason. Нажмите, чтобы продолжить.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason. Нажмите, чтобы продолжить запись."))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_RESUME, n) }
    }

    fun cancelResumeRequest(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_RESUME)

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code,
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
