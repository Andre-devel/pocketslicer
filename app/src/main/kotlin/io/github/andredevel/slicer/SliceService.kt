package io.github.andredevel.slicer

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.andredevel.slicer.core.SliceStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Mantém o processo em primeiro plano enquanto o motor fatia (o fatiamento continua no
 * SlicerViewModel), com o andamento numa notificação. Para sozinho quando [progress] volta a null.
 */
class SliceService : Service() {
    /** Andamento mostrado na notificação; o ViewModel publica e limpa ao terminar. */
    data class Progress(val plate: Int, val slice: SliceState.Running)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updates: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Obrigatório a cada startForegroundService, mesmo se o fatiamento já acabou.
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progressNotification(this, progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (updates == null) updates = scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            progress.collect { current ->
                if (current == null) {
                    ServiceCompat.stopForeground(this@SliceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // Um start mais novo, de outro fatiamento, mantém o serviço.
                    stopSelf(lastStartId)
                } else {
                    manager.notify(PROGRESS_ID, progressNotification(this@SliceService, current))
                    // O sistema descarta atualizações rápidas demais da mesma notificação.
                    delay(UPDATE_INTERVAL_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    // Limite do tipo dataSync (Android 15+): o fatiamento segue, só sem a notificação.
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        val progress = MutableStateFlow<Progress?>(null)

        /** Intent da notificação de fim: a MainActivity abre Visualizar. */
        const val ACTION_SHOW_PREVIEW = "io.github.andredevel.slicer.SHOW_PREVIEW"

        private const val CHANNEL = "slicing"
        private const val PROGRESS_ID = 1
        private const val DONE_ID = 2
        private const val UPDATE_INTERVAL_MS = 500L

        fun start(context: Context) {
            createChannel(context)
            context.getSystemService(NotificationManager::class.java).cancel(DONE_ID)
            ContextCompat.startForegroundService(context, Intent(context, SliceService::class.java))
        }

        /** Avisa o fim do fatiamento só com o app fora da tela; tocar abre Visualizar. */
        fun notifyDone(context: Context, plate: Int, stats: SliceStats) {
            val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
            if (state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val grams = String.format(context.resources.configuration.locales[0], "%.1f g", stats.filamentGrams)
            val notification = builder(context, Intent(context, MainActivity::class.java).setAction(ACTION_SHOW_PREVIEW))
                .setContentTitle(context.getString(R.string.slice_done_title, plate))
                .setContentText("${formatDuration(stats.printTimeSeconds)} · $grams")
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(DONE_ID, notification)
        }

        private fun progressNotification(context: Context, progress: Progress?): Notification {
            val slice = progress?.slice
            val stage = slice?.let { context.getString(it.stage.label) }
            val text = if (slice?.layer != null) context.getString(R.string.slice_stage_layer, stage, slice.layer) else stage
            return builder(context, Intent(context, MainActivity::class.java))
                .setContentTitle(context.getString(R.string.slicing_plate, progress?.plate ?: 1))
                .setContentText(text)
                .setProgress(100, slice?.percent ?: 0, slice == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun builder(context: Context, open: Intent): NotificationCompat.Builder {
            val intent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentIntent(intent)
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.slice_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
