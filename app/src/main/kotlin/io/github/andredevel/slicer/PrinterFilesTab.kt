package io.github.andredevel.slicer

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.GcodeInfo
import io.github.andredevel.slicer.send.PrintHistory
import io.github.andredevel.slicer.send.PrintJob
import io.github.andredevel.slicer.send.PrinterFile
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.SendException
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.MetricTile
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Aba Arquivos: os G-codes guardados na impressora, o mais novo primeiro, com a miniatura, o
 * tempo e o gasto lidos do próprio arquivo ([SlicerViewModel.printerFileInfo], conforme cada um
 * aparece); imprimir de novo (com a mesma confirmação e mapeamento do envio, [PrintConfirmDialog])
 * e apagar, com confirmação. Embaixo o histórico de impressões e os totais. Lê tudo de novo ao
 * abrir, ao tocar em atualizar e quando uma impressão começa ou termina; [onStarted] quando a
 * impressora aceitou iniciar um arquivo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrinterFilesTab(host: PrinterHost, status: PrinterStatus?, viewModel: SlicerViewModel, commands: CommandRunner, onStarted: () -> Unit) {
    val context = LocalContext.current
    var reload by remember(host) { mutableIntStateOf(0) }
    var files by remember(host) { mutableStateOf<List<PrinterFile>?>(null) }
    var history by remember(host) { mutableStateOf<PrintHistory?>(null) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    val busy = status?.busy == true
    LaunchedEffect(host, reload, busy) {
        failure = null
        try {
            files = viewModel.printerFiles(host)
            history = viewModel.printerHistory(host)
        } catch (e: SendException) {
            failure = context.getString(e.text, *e.args)
        }
    }
    val infos = remember(host) { mutableStateMapOf<PrinterFile, GcodeInfo>() }
    var shown by remember(host) { mutableIntStateOf(PAGE) }
    var printing by remember(host) { mutableStateOf<PrinterFile?>(null) }
    var deleting by remember(host) { mutableStateOf<PrinterFile?>(null) }

    failure?.let { Banner(Tone.ERROR, it, Modifier.fillMaxWidth(), actionLabel = stringResource(R.string.action_retry), onAction = { reload++ }) }
    val list = files
    SectionLabel(stringResource(R.string.printer_files) + (list?.let { " (${it.size})" } ?: "")) {
        AppIconButton(R.drawable.ic_refresh, stringResource(R.string.refresh), { reload++ })
    }
    when {
        list == null && failure == null -> Text(stringResource(R.string.printer_files_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list != null && list.isEmpty() -> Text(stringResource(R.string.printer_files_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for (file in list.orEmpty().take(shown)) {
        // Lido uma vez por arquivo (o uploader guarda), quando a linha aparece.
        LaunchedEffect(host, file) {
            if (file in infos) return@LaunchedEffect
            try {
                infos[file] = viewModel.printerFileInfo(host, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "info of ${file.path}", e)
            }
        }
        val current = busy && status?.file == file.path
        FileRow(
            file, infos[file], current, canPrint = !busy && !commands.sending, canDelete = !current && !commands.sending,
            onPrint = { printing = file }, onDelete = { deleting = file },
        )
    }
    if (list != null && list.size > shown) {
        AppButton(stringResource(R.string.show_more, list.size - shown), { shown += PAGE }, style = ButtonStyle.TEXT)
    }

    history?.let { h ->
        SectionLabel(stringResource(R.string.print_history))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            MetricTile(stringResource(R.string.history_jobs), "${h.totalJobs}", Modifier.weight(1f).fillMaxRowHeight())
            MetricTile(stringResource(R.string.history_print_time), formatDuration(h.printSeconds.toDouble()), Modifier.weight(1f).fillMaxRowHeight())
            MetricTile(stringResource(R.string.history_filament), meters(h.filamentMm), Modifier.weight(1f).fillMaxRowHeight())
        }
        if (h.jobs.isEmpty()) Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (job in h.jobs) {
            val file = list?.find { it.path == job.file }?.takeIf { job.exists }
            JobRow(job, canPrint = file != null && !busy && !commands.sending) { printing = file }
        }
    }

    printing?.let { file ->
        val info = infos[file]
        // Sem o rodapé do Orca (outro fatiador), o primeiro filamento.
        val filaments = info?.filaments.orEmpty()
        val used = filaments.map { it.index }.ifEmpty { listOf(0) }
        val count = used.max() + 1
        PrintConfirmDialog(
            host, viewModel, count, used,
            List(count) { i -> filaments.find { it.index == i }?.color?.let(::rgba) },
            List(count) { i -> filaments.find { it.index == i }?.type },
            List(count) { i -> filaments.find { it.index == i }?.type },
            stringResource(R.string.check_filament), { printing = null }, onRecolor = null, onReorder = null,
        ) { mapping ->
            printing = null
            commands.run {
                viewModel.startPrinterFile(host, file.path, mapping)
                onStarted()
            }
        }
    }
    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.file_delete_title)) },
            text = { Text(stringResource(R.string.file_delete_text, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    commands.run {
                        viewModel.deletePrinterFile(host, file)
                        reload++
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Miniatura, nome, data, tamanho, tempo e gasto; imprimir e apagar. */
@Composable
private fun FileRow(
    file: PrinterFile, info: GcodeInfo?, current: Boolean, canPrint: Boolean, canDelete: Boolean, onPrint: () -> Unit, onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(info) { info?.thumbnail?.let { decode(it) } }
    Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap, null, Modifier.size(56.dp), contentScale = ContentScale.Fit)
            else Icon(painterResource(R.drawable.ic_deployed_code), null, tint = MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(
                stringResource(R.string.file_printing).takeIf { current },
                dateTime(context, file.modified),
                Formatter.formatShortFileSize(context, file.size),
                info?.seconds?.let { formatDuration(it.toDouble()) },
                info?.grams?.let { "%.1f g".format(Locale.getDefault(), it) },
            )
            Text(parts.joinToString(" · "), style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AppIconButton(R.drawable.ic_play_arrow, stringResource(R.string.file_print), onPrint, enabled = canPrint)
        AppIconButton(R.drawable.ic_delete, stringResource(R.string.delete), onDelete, enabled = canDelete)
    }
}

/** Arquivo, como terminou, quando começou, quanto durou e o filamento; imprimir de novo se o arquivo ainda está lá. */
@Composable
private fun JobRow(job: PrintJob, canPrint: Boolean, onPrint: () -> Unit) {
    val context = LocalContext.current
    val (text, tone) = when (job.state) {
        PrintJob.State.COMPLETED -> R.string.job_completed to Tone.SUCCESS
        PrintJob.State.CANCELLED -> R.string.job_cancelled to Tone.WARNING
        PrintJob.State.ERROR -> R.string.job_error to Tone.ERROR
        PrintJob.State.INTERRUPTED -> R.string.job_interrupted to Tone.ERROR
        PrintJob.State.IN_PROGRESS -> R.string.job_in_progress to Tone.INFO
    }
    Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(job.file.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(text), style = MaterialTheme.typography.labelSmall, color = tone.color())
                val parts = listOfNotNull(
                    job.start.takeIf { it > 0 }?.let { dateTime(context, it) },
                    job.printSeconds.takeIf { it > 0 }?.let { formatDuration(it.toDouble()) },
                    job.filamentMm.takeIf { it > 0 }?.let(::meters),
                )
                Text(parts.joinToString(" · "), style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AppIconButton(R.drawable.ic_play_arrow, stringResource(R.string.file_print), onPrint, enabled = canPrint)
    }
}

private fun decode(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

private fun dateTime(context: android.content.Context, millis: Long): String {
    val date = Date(millis)
    return DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
}

private fun meters(mm: Double) = "%.1f m".format(Locale.getDefault(), mm / 1000)

/** "#RRGGBB" no formato das cores do projeto (RGBA de 0 a 1). */
private fun rgba(hex: String): FloatArray {
    val v = hex.removePrefix("#").toInt(16)
    return floatArrayOf(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f, 1f)
}

private const val TAG = "PrinterFiles"
/** Arquivos mostrados de cada vez: cada um lê o começo e o fim do arquivo. */
private const val PAGE = 20
