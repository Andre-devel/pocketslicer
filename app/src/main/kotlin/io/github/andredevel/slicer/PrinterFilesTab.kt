package io.github.andredevel.slicer

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Dp
import io.github.andredevel.slicer.ui.components.ContentCard
import io.github.andredevel.slicer.ui.components.Metric
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
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
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Aba Arquivos: em "Na impressora", os G-codes guardados, o mais novo primeiro, em cartões com a
 * miniatura, o tempo e o gasto lidos do próprio arquivo ([SlicerViewModel.printerFileInfo],
 * conforme cada um aparece); o tocado abre ao lado (na tela larga) ou por baixo (na estreita), com
 * os filamentos do arquivo, imprimir de novo (com a mesma confirmação e mapeamento do envio,
 * [PrintConfirmDialog]) e apagar, com confirmação. Em "Histórico", as impressões e os totais. Lê
 * tudo de novo ao abrir, ao tocar em atualizar e quando uma impressão começa ou termina;
 * [onStarted] quando a impressora aceitou iniciar um arquivo.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    var view by rememberSaveable(host) { mutableIntStateOf(0) }
    var selectedPath by rememberSaveable(host) { mutableStateOf<String?>(null) }
    var sheetOpen by remember(host) { mutableStateOf(false) }
    var printing by remember(host) { mutableStateOf<PrinterFile?>(null) }
    var deleting by remember(host) { mutableStateOf<PrinterFile?>(null) }
    val list = files

    failure?.let { Banner(Tone.ERROR, it, Modifier.fillMaxWidth(), actionLabel = stringResource(R.string.action_retry), onAction = { reload++ }) }
    BoxWithConstraints {
        val wide = maxWidth >= DETAIL_BESIDE
        val columns = ((if (wide) maxWidth - DETAIL_WIDTH - 16.dp else maxWidth) / CARD_MIN).toInt().coerceIn(2, 4)
        // Na tela larga sempre um aberto ao lado: o tocado ou o mais novo.
        val selected = list?.find { it.path == selectedPath } ?: list?.firstOrNull()?.takeIf { wide }
        fun detail(file: PrinterFile, modifier: Modifier) = @Composable {
            val current = busy && status?.file == file.path
            FileDetail(
                file, infos[file], current, canPrint = !busy && !commands.sending, canDelete = !current && !commands.sending,
                onPrint = { printing = file }, onDelete = { deleting = file }, modifier = modifier,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedTabs(
                    listOf(Segment(stringResource(R.string.files_on_printer)), Segment(stringResource(R.string.files_history))), view, { view = it },
                    if (wide) Modifier.width(340.dp) else Modifier.weight(1f), check = wide, compact = !wide,
                )
                // Na tela estreita o espaço fica para as duas opções.
                if (wide) {
                    Text(
                        if (view == 0) list?.let { pluralStringResource(R.plurals.files_count, it.size, it.size) } ?: "" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                AppIconButton(R.drawable.ic_refresh, stringResource(R.string.refresh), { reload++ })
            }
            if (view == 1) {
                history?.let { History(it, list, busy, commands) { file -> printing = file } }
                    ?: Text(stringResource(R.string.printer_files_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            when {
                list == null && failure == null -> Text(stringResource(R.string.printer_files_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list != null && list.isEmpty() -> Text(stringResource(R.string.printer_files_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val visible = list.orEmpty().take(shown)
            for (file in visible) {
                // Lido uma vez por arquivo (o uploader guarda), quando o cartão aparece.
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
            }
            val grid: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = columns,
                    ) {
                        for (file in visible) {
                            FileCard(file, infos[file], busy && status?.file == file.path, selected == file, Modifier.weight(1f)) {
                                selectedPath = file.path
                                if (!wide) sheetOpen = true
                            }
                        }
                        repeat((columns - visible.size % columns) % columns) { Spacer(Modifier.weight(1f)) }
                    }
                    if (list != null && list.size > shown) {
                        AppButton(stringResource(R.string.show_more, minOf(PAGE, list.size - shown)), { shown += PAGE }, style = ButtonStyle.OUTLINED)
                    }
                }
            }
            if (wide && selected != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    grid(Modifier.weight(1f))
                    detail(selected, Modifier.width(DETAIL_WIDTH))()
                }
            } else {
                grid(Modifier.fillMaxWidth())
            }
        }
        if (!wide && sheetOpen && selected != null) {
            // Aberta inteira e rolando: os botões não ficam escondidos embaixo.
            ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                detail(selected, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp))()
            }
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
            sheetOpen = false
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
                    sheetOpen = false
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

/** Totais do histórico e as impressões, cada uma com imprimir de novo se o arquivo ainda está lá. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun History(h: PrintHistory, files: List<PrinterFile>?, busy: Boolean, commands: CommandRunner, onPrint: (PrinterFile) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        MetricTile(stringResource(R.string.history_jobs), "${h.totalJobs}", Modifier.weight(1f).fillMaxRowHeight())
        MetricTile(stringResource(R.string.history_print_time), formatDuration(h.printSeconds.toDouble()), Modifier.weight(1f).fillMaxRowHeight())
        MetricTile(stringResource(R.string.history_filament), meters(h.filamentMm), Modifier.weight(1f).fillMaxRowHeight())
    }
    ContentCard(title = stringResource(R.string.print_history)) {
        if (h.jobs.isEmpty()) Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (job in h.jobs) {
            val file = files?.find { it.path == job.file }?.takeIf { job.exists }
            JobRow(job, canPrint = file != null && !busy && !commands.sending) { file?.let(onPrint) }
        }
    }
}

/** Cartão da grade: miniatura, nome e tempo, gasto e dia; borda na cor de destaque quando aberto. */
@Composable
private fun FileCard(file: PrinterFile, info: GcodeInfo?, current: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.surfaceContainerHigh else colors.surface,
        border = BorderStroke(2.dp, if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Thumbnail(info, Modifier.fillMaxWidth().height(120.dp), 44.dp)
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = listOf(
                info?.seconds?.let { formatDuration(it.toDouble()) } ?: "—",
                info?.grams?.let { grams(it) } ?: "—",
                day(context, file.modified),
            )
            Text(
                if (current) stringResource(R.string.file_printing) else parts.joinToString(" · "), style = MonoStyles.small,
                color = if (current) colors.primary else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Miniatura do arquivo, ou o ícone de peça (ícone riscado se o arquivo não tem miniatura). */
@Composable
private fun Thumbnail(info: GcodeInfo?, modifier: Modifier, iconSize: Dp) {
    val bitmap = remember(info) { info?.thumbnail?.let { decode(it) } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            info != null -> Icon(painterResource(R.drawable.ic_image_not_supported), null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.outlineVariant)
            else -> Icon(painterResource(R.drawable.ic_deployed_code), null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** O arquivo aberto: miniatura grande, quando foi enviado e o tamanho, tempo e gasto, os filamentos, apagar e imprimir de novo. */
@Composable
private fun FileDetail(
    file: PrinterFile, info: GcodeInfo?, current: Boolean, canPrint: Boolean, canDelete: Boolean,
    onPrint: () -> Unit, onDelete: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Thumbnail(info, Modifier.fillMaxWidth().height(180.dp), 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        stringResource(R.string.file_printing).takeIf { current },
                        stringResource(R.string.file_sent, dateTime(context, file.modified)),
                        Formatter.formatShortFileSize(context, file.size),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Row {
                Metric(stringResource(R.string.estimated_time), info?.seconds?.let { formatDuration(it.toDouble()) } ?: "—", Modifier.weight(1f))
                Metric(stringResource(R.string.filament), info?.grams?.let { grams(it) } ?: "—", Modifier.weight(1f))
            }
            val filaments = info?.filaments.orEmpty()
            if (filaments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.file_filaments), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    for (filament in filaments) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp).background(colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier.size(16.dp)
                                    .background(filament.color?.let { Color(android.graphics.Color.parseColor(it)) } ?: colors.outline, CircleShape)
                                    .border(1.dp, colors.outlineVariant, CircleShape),
                            )
                            Text(
                                listOfNotNull(filament.type, grams(filament.grams)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            Text(stringResource(R.string.filament_n, filament.index + 1), style = MonoStyles.small, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton(stringResource(R.string.delete), onDelete, style = ButtonStyle.TEXT, icon = R.drawable.ic_delete, enabled = canDelete, color = colors.error)
                AppButton(stringResource(R.string.file_print_again), onPrint, Modifier.weight(1f), icon = R.drawable.ic_replay, enabled = canPrint)
            }
        }
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

/** "hoje", "ontem" ou o dia e o mês. */
private fun day(context: android.content.Context, millis: Long): String = when {
    DateUtils.isToday(millis) -> context.getString(R.string.today)
    DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.yesterday)
    else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_NO_YEAR)
}

private fun grams(value: Double) = "%.0f g".format(Locale.getDefault(), value)

private fun meters(mm: Double) = "%.1f m".format(Locale.getDefault(), mm / 1000)

/** "#RRGGBB" no formato das cores do projeto (RGBA de 0 a 1). */
private fun rgba(hex: String): FloatArray {
    val v = hex.removePrefix("#").toInt(16)
    return floatArrayOf(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f, 1f)
}

private const val TAG = "PrinterFiles"
/** Na tela larga o arquivo aberto fica ao lado da grade. */
private val DETAIL_BESIDE = 840.dp
private val DETAIL_WIDTH = 400.dp
/** Largura mínima de um cartão da grade. */
private val CARD_MIN = 180.dp

/** Arquivos mostrados de cada vez: cada um lê o começo e o fim do arquivo. */
private const val PAGE = 20
