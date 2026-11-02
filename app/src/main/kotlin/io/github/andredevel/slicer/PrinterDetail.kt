package io.github.andredevel.slicer

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.Camera
import io.github.andredevel.slicer.send.FilamentBox
import io.github.andredevel.slicer.send.PrintAction
import io.github.andredevel.slicer.send.PrinterAlert
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.SendException
import io.github.andredevel.slicer.send.Slot
import io.github.andredevel.slicer.send.Temperature
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.MetricTile
import io.github.andredevel.slicer.ui.components.ScreenHeader
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Estado de uma impressora salva: câmera, impressão em curso (camada, tempos), temperaturas e
 * filamento (caixas com a umidade, posições carregadas). O estado vem de
 * [SlicerViewModel.watchPrinterStatus], que a tela de Impressoras já mantém; [onEdit] abre o
 * cadastro e [onClose] (tela estreita) volta à lista.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterDetail(host: PrinterHost, status: PrinterStatus?, viewModel: SlicerViewModel, modifier: Modifier, onEdit: () -> Unit, onClose: (() -> Unit)?) {
    // Procurada uma vez por impressora; numa falha de conexão, de novo depois.
    var camera by remember(host) { mutableStateOf<Camera?>(null) }
    LaunchedEffect(host) {
        while (true) {
            try {
                camera = viewModel.printerCamera(host)
                break
            } catch (e: SendException) {
                Log.w(TAG, "camera of ${host.name}", e)
            }
            delay(SLOTS_INTERVAL_MS)
        }
    }
    var slots by remember(host) { mutableStateOf(emptyList<Slot>()) }
    LaunchedEffect(host) {
        while (true) {
            try {
                slots = viewModel.printerSlots(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fica com a última lista; o estado ao lado já mostra a falha de conexão.
                Log.w(TAG, "slots of ${host.name}", e)
            }
            delay(SLOTS_INTERVAL_MS)
        }
    }

    Column(modifier) {
        ScreenHeader(host.name, subtitle = host.address) {
            if (onClose != null) AppIconButton(R.drawable.ic_close, stringResource(R.string.close), onClose)
            AppButton(stringResource(R.string.edit), onEdit, style = ButtonStyle.TONAL, icon = R.drawable.ic_edit)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status != null) PrinterStatusDot(status) else Text(stringResource(R.string.printer_status_loading), style = MaterialTheme.typography.labelSmall)
            status?.alert?.let { PrinterAlertBanner(it) }
            status?.let { HumidityBanners(it) }
            camera?.let {
                CameraView(it, Modifier.fillMaxWidth().widthIn(max = 720.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium))
            }
            if (status != null && status.busy) PrintProgress(status)
            if (status != null && status.busy && viewModel.canControlPrinter(host)) PrintControls(host, status, viewModel)
            if (status != null && status.temperatures.isNotEmpty()) {
                SectionLabel(stringResource(R.string.temperatures))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                    for (temperature in status.temperatures) {
                        MetricTile(
                            temperatureName(temperature, status.temperatures),
                            "%.0f °C".format(Locale.ROOT, temperature.current),
                            Modifier.weight(1f).fillMaxRowHeight(),
                            detail = temperature.target?.let {
                                if (it > 0) stringResource(R.string.temperature_target, "%.0f".format(Locale.ROOT, it)) else stringResource(R.string.heater_off)
                            },
                        )
                    }
                }
            }
            if (slots.isNotEmpty() || status?.boxes?.isNotEmpty() == true) {
                SectionLabel(stringResource(R.string.filament))
                for (box in status?.boxes.orEmpty()) BoxRow(box)
                for (slot in slots) SlotRow(slot)
            }
        }
    }
}

/** Barra de progresso, arquivo, camada e tempos (decorrido, restante e a hora prevista do fim). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrintProgress(status: PrinterStatus) {
    SectionLabel(stringResource(R.string.current_print))
    status.file?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    status.progress?.let { progress ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator({ progress }, Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", style = MonoStyles.small)
        }
    }
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        if (status.layer != null) {
            MetricTile(stringResource(R.string.layer), status.layers?.let { "${status.layer} / $it" } ?: "${status.layer}", Modifier.weight(1f).fillMaxRowHeight())
        }
        status.elapsed?.let { MetricTile(stringResource(R.string.elapsed_time), formatDuration(it.toDouble()), Modifier.weight(1f).fillMaxRowHeight()) }
        status.remaining?.let {
            // A hora do fim só imprimindo: pausada, ela não anda.
            val end = if (status.state == PrinterStatus.State.PRINTING) DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + it * 1000)) else null
            MetricTile(stringResource(R.string.remaining_time), formatDuration(it.toDouble()), Modifier.weight(1f).fillMaxRowHeight(), detail = end?.let { time -> stringResource(R.string.ends_at, time) })
        }
    }
}

/**
 * Pausar ou continuar e parar a impressão, como na tela da impressora; parar não tem volta e
 * pede confirmação. Até o estado seguinte chegar os botões ficam desligados.
 */
@Composable
private fun PrintControls(host: PrinterHost, status: PrinterStatus, viewModel: SlicerViewModel) {
    val scope = rememberCoroutineScope()
    var sending by remember(host) { mutableStateOf(false) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    var confirmStop by remember(host) { mutableStateOf(false) }
    val context = LocalContext.current
    fun run(action: PrintAction) {
        sending = true
        failure = null
        scope.launch {
            try {
                viewModel.printerControl(host, action)
            } catch (e: SendException) {
                failure = context.getString(e.text, *e.args)
            } finally {
                sending = false
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.state == PrinterStatus.State.PAUSED) {
            AppButton(stringResource(R.string.print_resume), { run(PrintAction.RESUME) }, icon = R.drawable.ic_play_arrow, enabled = !sending)
        } else {
            AppButton(stringResource(R.string.print_pause), { run(PrintAction.PAUSE) }, style = ButtonStyle.TONAL, icon = R.drawable.ic_pause, enabled = !sending)
        }
        AppButton(
            stringResource(R.string.print_stop), { confirmStop = true }, style = ButtonStyle.OUTLINED, icon = R.drawable.ic_stop,
            enabled = !sending, color = MaterialTheme.colorScheme.error,
        )
    }
    failure?.let { Banner(Tone.ERROR, it, Modifier.fillMaxWidth()) }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.print_stop_title, host.name)) },
            text = { Text(listOfNotNull(status.file, stringResource(R.string.print_stop_text)).joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    run(PrintAction.STOP)
                }) { Text(stringResource(R.string.print_stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Erro que a impressora mostra na tela dela, com o texto dos códigos que o app conhece. */
@Composable
private fun PrinterAlertBanner(alert: PrinterAlert) {
    val text = when (alert.number) {
        101, 500 -> R.string.printer_alert_ai_quality
        103, 503 -> R.string.printer_alert_ai_first_layer
        104 -> R.string.printer_alert_ai_foreign_object
        else -> R.string.printer_alert_unknown
    }
    Banner(Tone.ERROR, stringResource(text), Modifier.fillMaxWidth(), title = stringResource(R.string.printer_alert_title, alert.code))
}

/** Aviso de cada caixa de filamento úmida ([FilamentBox.humid]). */
@Composable
fun HumidityBanners(status: PrinterStatus, modifier: Modifier = Modifier) {
    for (box in status.boxes.filter { it.humid }) {
        Banner(Tone.WARNING, stringResource(R.string.humidity_high_text), modifier.fillMaxWidth(), title = stringResource(R.string.humidity_high_title, box.name, humidityText(box)))
    }
}

/** Aviso curto para a lista de impressoras e o envio: "Umidade alta: CFS 1 65%". */
@Composable
fun HumidityWarning(status: PrinterStatus, modifier: Modifier = Modifier) {
    val humid = status.boxes.filter { it.humid }
    if (humid.isEmpty()) return
    val boxes = humid.map { "${it.name} ${humidityText(it)}" }.joinToString()
    Text(
        stringResource(R.string.humidity_warning, boxes),
        style = MaterialTheme.typography.labelSmall,
        color = Tone.WARNING.color(),
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "65%" ou, no AMS que só dá o nível, "nível 2 de 5". */
@Composable
private fun humidityText(box: FilamentBox): String =
    box.humidity?.let { "$it%" } ?: box.humidityLevel?.let { stringResource(R.string.humidity_level, it) }.orEmpty()

@Composable
private fun BoxRow(box: FilamentBox) {
    val parts = listOfNotNull(
        humidityText(box).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.humidity_value, it) },
        box.temperature?.let { "%.0f °C".format(Locale.ROOT, it) },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(box.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            parts.joinToString(" · "),
            style = MonoStyles.small,
            color = if (box.humid) Tone.WARNING.color() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlotRow(slot: Slot) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SlotSwatch(slot.takeIf { it.loaded })
        Text(slotName(slot), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (slot.loaded) slot.type ?: "?" else stringResource(R.string.slot_empty),
            style = MonoStyles.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Bico", "Bico 2" (com vários), "Mesa", "Câmara". */
@Composable
private fun temperatureName(temperature: Temperature, all: List<Temperature>): String = when (temperature.source) {
    Temperature.Source.NOZZLE ->
        if (all.count { it.source == Temperature.Source.NOZZLE } > 1) stringResource(R.string.nozzle_n, temperature.index + 1)
        else stringResource(R.string.nozzle)
    Temperature.Source.BED -> stringResource(R.string.bed)
    Temperature.Source.CHAMBER -> stringResource(R.string.chamber)
}

private const val TAG = "PrinterDetail"
/** As posições mudam pouco (e na Creality cada consulta abre o WebSocket); também a espera para procurar a câmera de novo. */
private const val SLOTS_INTERVAL_MS = 15_000L
