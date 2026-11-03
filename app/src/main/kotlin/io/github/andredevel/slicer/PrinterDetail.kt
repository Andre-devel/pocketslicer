package io.github.andredevel.slicer

import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.andredevel.slicer.send.Camera
import io.github.andredevel.slicer.send.FilamentBox
import io.github.andredevel.slicer.send.FilamentControls
import io.github.andredevel.slicer.send.PrinterAlert
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterControls
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
import io.github.andredevel.slicer.ui.components.IconTab
import io.github.andredevel.slicer.ui.components.IconTabs
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tela de uma impressora salva. Visão geral: câmera, impressão em curso (camada, tempos),
 * temperaturas e filamento (caixas com a umidade, posições carregadas), com os ajustes da
 * impressão em andamento ([PrintAdjustments]); Controle
 * ([PrinterControlTab]), quando a impressora tem o que mexer; Arquivos ([PrinterFilesTab]); Manutenção
 * ([PrinterMaintenanceTab]), com a calibração em andamento ([maintenance]); Console ([PrinterConsoleTab]). A parada de emergência fica no
 * cabeçalho e o reinício do firmware aparece quando o Klipper parou. O estado vem de
 * [SlicerViewModel.watchPrinterStatus], que a tela de Impressoras já mantém, e enquanto esta tela
 * está aberta e visível, ao vivo ([SlicerViewModel.watchLiveStatus]); [onEdit] abre o
 * cadastro, [onClose] (tela estreita) volta à lista e [onToggleList] (tela larga) esconde ou
 * mostra a lista ao lado, que [listOpen] diz se está aberta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterDetail(
    host: PrinterHost, status: PrinterStatus?, viewModel: SlicerViewModel, modifier: Modifier, onEdit: () -> Unit, onClose: (() -> Unit)?,
    listOpen: Boolean = true, onToggleList: (() -> Unit)? = null, filamentQueue: List<FilamentTask> = emptyList(),
    maintenance: MaintenanceRun? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchLiveStatus(host) }
    }
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
    // De novo a cada operação da fila que termina: a posição mudou.
    val filamentDone = filamentQueue.count { it.state == FilamentTask.State.DONE }
    LaunchedEffect(host, filamentDone) {
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

    // O que a aba Controle mostra; de novo quando o Klipper volta (o reinício pode mudar a configuração).
    var controls by remember(host) { mutableStateOf<PrinterControls?>(null) }
    val halted = status?.halted == true
    LaunchedEffect(host, halted) {
        if (halted) return@LaunchedEffect
        while (true) {
            try {
                controls = viewModel.printerControls(host)?.takeIf {
                    it.hasControlTab || !it.adjustments.isEmpty || !it.filament.isEmpty || it.excludeObject || it.maintenance.isNotEmpty()
                }
                break
            } catch (e: SendException) {
                Log.w(TAG, "controls of ${host.name}", e)
            }
            delay(SLOTS_INTERVAL_MS)
        }
    }
    // As abas que esta impressora tem; a escolhida fica pelo nome (a Controle aparece depois de ler as capacidades).
    val tabs = listOfNotNull(
        PrinterTab.OVERVIEW,
        PrinterTab.CONTROL.takeIf { controls?.hasControlTab == true },
        PrinterTab.FILES.takeIf { viewModel.printerHasFiles(host) },
        PrinterTab.MAINTENANCE.takeIf { controls?.maintenance?.isNotEmpty() == true },
        PrinterTab.CONSOLE.takeIf { viewModel.printerHasConsole(host) },
    )
    var chosen by rememberSaveable(host.id) { mutableStateOf(PrinterTab.OVERVIEW) }
    val tab = chosen.takeIf { it in tabs } ?: PrinterTab.OVERVIEW

    val commands = rememberCommandRunner(host, viewModel)
    var confirmEmergency by remember(host) { mutableStateOf(false) }
    val canEmergencyStop = viewModel.printerSupports(host, PrinterCommand.EmergencyStop) &&
        status != null && status.state != PrinterStatus.State.OFFLINE && !status.halted
    Column(modifier) {
        ScreenHeader(host.name, subtitle = host.address) {
            if (onToggleList != null) {
                if (listOpen) AppIconButton(R.drawable.ic_open_in_full, stringResource(R.string.printers_list_hide), onToggleList)
                else AppIconButton(R.drawable.ic_close_fullscreen, stringResource(R.string.printers_list_show), onToggleList)
            }
            if (onClose != null) AppIconButton(R.drawable.ic_close, stringResource(R.string.close), onClose)
            // Na tela estreita só o ícone, para caber o nome e a parada de emergência.
            if (onClose != null) AppIconButton(R.drawable.ic_edit, stringResource(R.string.edit), onEdit)
            else AppButton(stringResource(R.string.edit), onEdit, style = ButtonStyle.TONAL, icon = R.drawable.ic_edit)
            if (canEmergencyStop) {
                AppButton(
                    stringResource(R.string.emergency_stop), { confirmEmergency = true }, icon = R.drawable.ic_block,
                    enabled = !commands.sending, color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // A recusa de um comando aparece por cima, no rodapé: não muda o lugar dos botões sob o dedo.
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status != null) PrinterStatusDot(status) else Text(stringResource(R.string.printer_status_loading), style = MaterialTheme.typography.labelSmall)
                if (status?.halted == true && viewModel.printerSupports(host, PrinterCommand.FirmwareRestart)) {
                    Banner(
                        Tone.ERROR, status.raw ?: stringResource(R.string.printer_halted_text), Modifier.fillMaxWidth(),
                        title = stringResource(R.string.printer_halted),
                        actionLabel = stringResource(R.string.firmware_restart).takeIf { !commands.sending },
                        onAction = { commands.run(PrinterCommand.FirmwareRestart) },
                    )
                }
                status?.alert?.let { PrinterAlertBanner(it) }
                val control = controls
                if (tabs.size > 1) {
                    // Com ícone e rolando na horizontal: cinco abas não cabem lado a lado no celular.
                    IconTabs(tabs.map { IconTab(it.icon, stringResource(it.label)) }, tabs.indexOf(tab), { chosen = tabs[it] })
                }
                if (tab == PrinterTab.CONTROL && control != null) {
                    PrinterControlTab(host, status, control, commands)
                } else if (tab == PrinterTab.MAINTENANCE && control != null) {
                    PrinterMaintenanceTab(host, status, control.maintenance, maintenance, filamentQueue.any { it.state == FilamentTask.State.RUNNING }, viewModel)
                } else if (tab == PrinterTab.CONSOLE) {
                    PrinterConsoleTab(host, status, viewModel)
                } else if (tab == PrinterTab.FILES) {
                    PrinterFilesTab(host, status, viewModel, commands) { chosen = PrinterTab.OVERVIEW }
                } else {
                    status?.let { HumidityBanners(it) }
                    camera?.let {
                        CameraView(it, Modifier.fillMaxWidth().widthIn(max = 720.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium))
                    }
                    if (status != null && status.busy) PrintProgress(status)
                    if (status != null && status.busy && viewModel.printerSupports(host, PrinterCommand.Stop)) PrintControls(host, status, commands)
                    if (status != null && status.busy && control != null && !control.adjustments.isEmpty) PrintAdjustments(status, control.adjustments, commands)
                    if (status != null && status.busy && control?.excludeObject == true && status.objects.isNotEmpty()) PrintObjects(status, commands)
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
                        PrinterFilament(host, status, slots, control?.filament ?: FilamentControls(), filamentQueue, viewModel, commands)
                    }
                }
            }
            commands.failure?.let {
                Banner(
                    Tone.ERROR, it, Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 720.dp).fillMaxWidth(),
                    onDismiss = commands::dismiss,
                )
            }
        }
    }
    // Corta tudo na hora e perde a impressão: confirma, mas com um toque só.
    if (confirmEmergency) {
        AlertDialog(
            onDismissRequest = { confirmEmergency = false },
            title = { Text(stringResource(R.string.emergency_stop_title, host.name)) },
            text = { Text(stringResource(R.string.emergency_stop_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmergency = false
                    commands.run(PrinterCommand.EmergencyStop)
                }) { Text(stringResource(R.string.emergency_stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmergency = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private enum class PrinterTab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    OVERVIEW(R.string.printer_tab_overview, R.drawable.ic_info), CONTROL(R.string.printer_tab_control, R.drawable.ic_open_with),
    FILES(R.string.printer_tab_files, R.drawable.ic_folder_open), MAINTENANCE(R.string.printer_tab_maintenance, R.drawable.ic_build),
    CONSOLE(R.string.printer_tab_console, R.drawable.ic_terminal),
}

/**
 * Manda os comandos da tela de uma impressora, um por vez: [sending] enquanto a impressora não
 * aceitou, [failure] com a resposta dela quando recusou.
 */
@Stable
internal class CommandRunner(private val host: PrinterHost, private val viewModel: SlicerViewModel, private val scope: CoroutineScope, private val context: android.content.Context) {
    var sending by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    fun dismiss() {
        failure = null
    }

    fun run(command: PrinterCommand) = run { viewModel.printerCommand(host, command) }

    /** Outro pedido à impressora com o mesmo [sending] e [failure] (apagar um arquivo, iniciar um). */
    fun run(action: suspend () -> Unit) {
        sending = true
        failure = null
        scope.launch {
            try {
                action()
            } catch (e: SendException) {
                failure = context.getString(e.text, *e.args)
            } finally {
                sending = false
            }
        }
    }
}

@Composable
private fun rememberCommandRunner(host: PrinterHost, viewModel: SlicerViewModel): CommandRunner {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(host) { CommandRunner(host, viewModel, scope, context) }
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
private fun PrintControls(host: PrinterHost, status: PrinterStatus, commands: CommandRunner) {
    var confirmStop by remember(host) { mutableStateOf(false) }
    val sending = commands.sending
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.state == PrinterStatus.State.PAUSED) {
            AppButton(stringResource(R.string.print_resume), { commands.run(PrinterCommand.Resume) }, icon = R.drawable.ic_play_arrow, enabled = !sending)
        } else {
            AppButton(stringResource(R.string.print_pause), { commands.run(PrinterCommand.Pause) }, style = ButtonStyle.TONAL, icon = R.drawable.ic_pause, enabled = !sending)
        }
        AppButton(
            stringResource(R.string.print_stop), { confirmStop = true }, style = ButtonStyle.OUTLINED, icon = R.drawable.ic_stop,
            enabled = !sending, color = MaterialTheme.colorScheme.error,
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.print_stop_title, host.name)) },
            text = { Text(listOfNotNull(status.file, stringResource(R.string.print_stop_text)).joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    commands.run(PrinterCommand.Stop)
                }) { Text(stringResource(R.string.print_stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Erro que a impressora mostra na tela dela, com o texto dos códigos que o app conhece. */
@Composable
private fun PrinterAlertBanner(alert: PrinterAlert) {
    Banner(Tone.ERROR, stringResource(alertText(alert)), Modifier.fillMaxWidth(), title = stringResource(R.string.printer_alert_title, alert.code))
}

/** O que o alerta da tela da impressora quer dizer (também no aviso de [PrintWatchService]). */
@StringRes
internal fun alertText(alert: PrinterAlert) = when (alert.number) {
    101, 500 -> R.string.printer_alert_ai_quality
    103, 503 -> R.string.printer_alert_ai_first_layer
    104 -> R.string.printer_alert_ai_foreign_object
    else -> R.string.printer_alert_unknown
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
