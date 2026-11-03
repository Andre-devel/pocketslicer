package io.github.andredevel.slicer

import android.Manifest
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.PrinterStore
import io.github.andredevel.slicer.send.SendException
import io.github.andredevel.slicer.send.uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Acompanha as impressões com o app em segundo plano e avisa quando uma termina, falha ou é
 * cancelada, pausa sem ter sido pelo app (fim de filamento, alerta da impressora) ou a impressora
 * some da rede no meio dela. Roda em primeiro plano (o Android exige a notificação fixa) só
 * enquanto alguma impressora salva imprime: o [SlicerViewModel] o liga ao ver uma imprimindo com o
 * app aberto, e ele para sozinho quando nenhuma imprime mais. Consulta todas a cada
 * [POLL_INTERVAL_MS], como a lista de impressoras.
 */
class PrintWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    /** O que se sabe de cada impressora ([PrinterHost.id]) desde a última consulta. */
    private class Watch(val status: PrinterStatus, val file: String?, val offlineSince: Long? = null, val offlineNotified: Boolean = false)

    private val watches = mutableMapOf<String, Watch>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        createChannels(this)
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (loop == null) loop = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val store = PrinterStore(File(filesDir, PRINTERS_FILE))
        val manager = getSystemService(NotificationManager::class.java)
        while (true) {
            val printers = try {
                store.load()
            } catch (e: Exception) {
                Log.w(TAG, "printers", e)
                emptyList()
            }
            val statuses = coroutineScope { printers.map { host -> async { host to status(host) } }.awaitAll() }
            val now = System.currentTimeMillis()
            val printing = mutableListOf<Pair<PrinterHost, PrinterStatus>>()
            for ((host, status) in statuses) {
                val before = watches[host.id]
                watches[host.id] = next(host, before, status, now)
                if (status.busy) printing += host to status
            }
            watches.keys.retainAll(printers.map { it.id }.toSet())
            // Sumiu no meio da impressão: segue olhando por um tempo, para avisar e ver se volta.
            val waiting = watches.values.any { it.offlineSince != null && now - it.offlineSince < OFFLINE_WATCH_MS }
            if (printing.isEmpty() && !waiting) break
            val offline = printers.filter { host -> watches[host.id]?.offlineSince?.let { now - it < OFFLINE_WATCH_MS } == true }
            manager.notify(ONGOING_ID, ongoing(printing, offline))
            delay(POLL_INTERVAL_MS)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun status(host: PrinterHost): PrinterStatus = try {
        uploader(host.kind, assets).status(host)
    } catch (e: SendException) {
        if (e.text == R.string.send_error_unreachable) PrinterStatus(PrinterStatus.State.OFFLINE)
        else PrinterStatus(PrinterStatus.State.ERROR, raw = getString(e.text, *e.args))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "status of ${host.name}", e)
        PrinterStatus(PrinterStatus.State.ERROR, raw = e.toString())
    }

    /** O novo [Watch] de [host], avisando o que mudou desde [before]. Na primeira consulta, só guarda. */
    private fun next(host: PrinterHost, before: Watch?, status: PrinterStatus, now: Long): Watch {
        if (before == null) return Watch(status, status.file)
        val was = before.status
        // Fora da rede: o estado de antes (imprimindo) fica guardado até ela voltar.
        if (status.state == PrinterStatus.State.OFFLINE) {
            if (!was.busy && before.offlineSince == null) return Watch(status, null)
            val since = before.offlineSince ?: now
            val notify = !before.offlineNotified && now - since >= OFFLINE_NOTIFY_MS
            if (notify) event(host, getString(R.string.watch_offline_title, host.name), getString(R.string.watch_offline_text, before.file ?: ""))
            return Watch(was, before.file, since, before.offlineNotified || notify)
        }
        val file = status.file ?: before.file
        when {
            was.busy && !status.busy -> if (!expected(host)) finished(host, status, before.file)
            was.state == PrinterStatus.State.PRINTING && status.state == PrinterStatus.State.PAUSED && !expected(host) -> event(
                host, getString(R.string.watch_paused_title, host.name),
                status.alert?.let { getString(alertText(it)) } ?: before.file ?: getString(R.string.watch_paused_text),
            )
        }
        return Watch(status, if (status.busy) file else null)
    }

    /** A impressão de [host] acabou: como ela informa (concluída, cancelada, erro). */
    private fun finished(host: PrinterHost, status: PrinterStatus, file: String?) {
        val name = file ?: ""
        when {
            status.halted || status.state == PrinterStatus.State.ERROR || status.raw in ERROR_STATES ->
                event(host, getString(R.string.watch_error_title, host.name), status.raw?.takeIf { it !in ERROR_STATES } ?: name)
            status.raw in CANCELLED_STATES -> event(host, getString(R.string.watch_cancelled_title, host.name), name)
            status.raw in COMPLETE_STATES -> event(host, getString(R.string.watch_done_title, host.name), name)
            else -> event(host, getString(R.string.watch_ended_title, host.name), name)
        }
    }

    private fun event(host: PrinterHost, title: String, text: String) {
        Log.i(TAG, "${host.name}: $title")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(this, EVENTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_print)
            .setContentTitle(title)
            .setContentText(text.takeIf { it.isNotEmpty() })
            .setContentIntent(open(this))
            .setAutoCancel(true)
            .build()
        // Um aviso por impressora: o mais novo substitui o anterior.
        getSystemService(NotificationManager::class.java).notify(EVENT_TAG, host.id.hashCode(), notification)
    }

    /** A notificação fixa: as que imprimem, com o progresso, e as que sumiram no meio ([offline]). */
    private fun ongoing(printing: List<Pair<PrinterHost, PrinterStatus>>, offline: List<PrinterHost> = emptyList()): Notification {
        val parts = printing.map { (host, status) -> status.progress?.let { "${host.name} ${(it * 100).toInt()}%" } ?: host.name } +
            offline.map { getString(R.string.watch_ongoing_offline, it.name) }
        val text = parts.joinToString(" · ")
        val count = (printing.size + offline.size).coerceAtLeast(1)
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_print)
            .setContentTitle(resources.getQuantityString(R.plurals.watch_ongoing, count, count))
            .setContentText(text.takeIf { it.isNotEmpty() })
            .setContentIntent(open(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PrintWatch"
        private const val PRINTERS_FILE = "printers.json"
        private const val ONGOING_CHANNEL = "print_watch"
        private const val EVENTS_CHANNEL = "print_events"
        private const val ONGOING_ID = 10
        private const val EVENT_TAG = "print_event"
        private const val POLL_INTERVAL_MS = 30_000L

        /** Fora da rede por este tempo no meio da impressão: avisa (um tropeço do Wi-Fi não). */
        private const val OFFLINE_NOTIFY_MS = 60_000L

        /** Quanto tempo segue olhando uma impressora que sumiu imprimindo. */
        private const val OFFLINE_WATCH_MS = 60 * 60_000L

        /** Pausa ou parada pedida pelo app vale por este tempo: não é aviso. */
        private const val EXPECTED_MS = 120_000L

        // print_stats.state do Klipper e gcode_state da Bambu.
        private val COMPLETE_STATES = setOf("complete", "FINISH")
        private val CANCELLED_STATES = setOf("cancelled")
        private val ERROR_STATES = setOf("error", "FAILED")

        /** Se o serviço está rodando (ligar de novo só repetiria o startForeground). */
        @Volatile
        var running = false
            private set

        /** Quando o app pediu pausa ou parada em cada impressora ([PrinterHost.id]). */
        private val requested = ConcurrentHashMap<String, Long>()

        /** O app pediu pausa ou parada em [host]: a mudança que vem não é aviso. */
        fun expect(host: PrinterHost) {
            requested[host.id] = System.currentTimeMillis()
        }

        private fun expected(host: PrinterHost) = requested[host.id]?.let { System.currentTimeMillis() - it < EXPECTED_MS } == true

        /** Liga o acompanhamento; só dá com o app em primeiro plano (o Android recusa de fundo). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PrintWatchService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "start", e)
            }
        }

        private fun open(context: Context) = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, context.getString(R.string.watch_channel), NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(EVENTS_CHANNEL, context.getString(R.string.watch_events_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
