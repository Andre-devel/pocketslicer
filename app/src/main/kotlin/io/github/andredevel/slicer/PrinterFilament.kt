package io.github.andredevel.slicer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.FilamentBox
import io.github.andredevel.slicer.send.FilamentControls
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.Slot
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.ContentCard
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Locale

/**
 * Aba Filamento: as posições de [host] ([slots]) por caixa ([FilamentBox], com a umidade e a
 * temperatura) e, ao lado na tela larga ou embaixo na estreita, a fila de carga e descarga
 * ([queue]). Com [FilamentControls.loadUnload], o usuário marca várias posições e toca uma vez em
 * Carregar ou Descarregar, e o ViewModel faz uma por vez; com [FilamentControls.refresh], cada
 * posição relê o RFID. Só com a impressora livre.
 */
@Composable
internal fun PrinterFilamentTab(
    host: PrinterHost, status: PrinterStatus?, slots: List<Slot>, filament: FilamentControls, queue: List<FilamentTask>,
    viewModel: SlicerViewModel, commands: CommandRunner,
) {
    val idle = status?.state == PrinterStatus.State.IDLE && !status.halted
    val queueRunning = queue.any { it.state == FilamentTask.State.RUNNING }
    var selected by remember(host) { mutableStateOf(emptySet<Int>()) }
    // Uma posição que saiu da lista (caixa desligada) não fica marcada sem aparecer.
    val chosen = slots.filter { it.index in selected }
    val boxes = status?.boxes.orEmpty()
    // As caixas na ordem da impressora; as posições fora de uma (carretel externo, cabeças) por último.
    val units = (boxes.map { it.unit } + slots.map { it.unit }).distinct().sortedWith(nullsLast(compareBy { it }))
    val showQueue = filament.loadUnload || queue.isNotEmpty()

    BoxWithConstraints {
        val wide = maxWidth >= WIDE
        val cards = maxWidth >= SLOT_CARDS
        val main: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                status?.let { HumidityBanners(it) }
                for (unit in units) {
                    val box = boxes.firstOrNull { it.unit == unit }
                    val inBox = slots.filter { it.unit == unit }
                    // Carga, descarga e RFID só nas caixas (o carretel externo e as cabeças não têm).
                    BoxCard(box, unit == null && units.size > 1, inBox, cards, if (unit == null) FilamentControls() else filament, selected, idle, refreshEnabled = idle && !queueRunning && !commands.sending,
                        onToggle = { slot, on -> selected = if (on) selected + slot.index else selected - slot.index },
                        onRefresh = { commands.run(PrinterCommand.RefreshSlot(it)) })
                }
                if (filament.loadUnload && slots.isNotEmpty()) {
                    SelectionBar(chosen.size, idle, wide, onClear = { selected = emptySet() }) { load ->
                        viewModel.queueFilament(host, chosen, load)
                        selected = emptySet()
                    }
                }
            }
        }
        when {
            wide && showQueue -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                main(Modifier.weight(1f))
                FilamentQueue(host, queue, viewModel, Modifier.width(QUEUE_WIDTH))
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                main(Modifier.fillMaxWidth())
                if (showQueue) FilamentQueue(host, queue, viewModel, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Uma caixa de filamento ([box], ou as posições fora de uma quando [external]) e as posições dela. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxCard(
    box: FilamentBox?, external: Boolean, slots: List<Slot>, cards: Boolean, filament: FilamentControls, selected: Set<Int>, idle: Boolean,
    refreshEnabled: Boolean, onToggle: (Slot, Boolean) -> Unit, onRefresh: (Slot) -> Unit,
) {
    val title = box?.name ?: stringResource(if (external) R.string.external_spool else R.string.filament)
    ContentCard(title = title, trailing = { if (box != null) BoxReading(box) }) {
        if (cards) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = SLOTS_PER_ROW) {
                for (slot in slots) {
                    SlotCard(slot, filament, slot.index in selected, idle, refreshEnabled, { onToggle(slot, it) }, { onRefresh(slot) }, Modifier.weight(1f))
                }
                // A última linha com as posições do mesmo tamanho das de cima.
                repeat((SLOTS_PER_ROW - slots.size % SLOTS_PER_ROW) % SLOTS_PER_ROW) { Spacer(Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (slot in slots) SlotRow(slot, filament, slot.index in selected, idle, refreshEnabled, { onToggle(slot, it) }, { onRefresh(slot) })
            }
        }
    }
}

/** Umidade e temperatura dentro da caixa, em amarelo quando úmida. */
@Composable
private fun BoxReading(box: FilamentBox) {
    val parts = listOfNotNull(humidityText(box).takeIf { it.isNotEmpty() }, box.temperature?.let { "%.0f °C".format(Locale.ROOT, it) })
    if (parts.isEmpty()) return
    Text(parts.joinToString(" · "), style = MonoStyles.small, color = if (box.humid) Tone.WARNING.color() else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Posição em cartão (tela larga): nome e marca, a cor em faixa, o material e a releitura do RFID. */
@Composable
private fun SlotCard(
    slot: Slot, filament: FilamentControls, checked: Boolean, idle: Boolean, refreshEnabled: Boolean,
    onToggle: (Boolean) -> Unit, onRefresh: () -> Unit, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val toggle = if (filament.loadUnload) Modifier.toggleable(checked, enabled = idle, role = Role.Checkbox, onValueChange = onToggle) else Modifier
    Surface(
        modifier.then(toggle),
        shape = RoundedCornerShape(14.dp),
        color = if (slot.loaded) colors.surfaceContainerLow else colors.surfaceContainerLowest,
        border = BorderStroke(2.dp, if (checked) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slotName(slot), style = MonoStyles.value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (slot.loaded) colors.onSurface else colors.onSurfaceVariant,
                )
                if (filament.loadUnload) Checkbox(checked = checked, onCheckedChange = null, enabled = idle, modifier = Modifier.size(28.dp))
            }
            val band = Modifier.fillMaxWidth().height(44.dp)
            val color = slotColor(slot)
            if (slot.loaded && color != null) {
                Box(band.background(color, RoundedCornerShape(10.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)))
            } else {
                Box(band.border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    if (!slot.loaded) Icon(painterResource(R.drawable.ic_block), null, Modifier.size(20.dp), tint = colors.outline)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (slot.loaded) slot.type ?: "?" else stringResource(R.string.slot_empty), style = MaterialTheme.typography.titleSmall,
                    color = if (slot.loaded) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (filament.refresh) {
                    AppIconButton(R.drawable.ic_contactless, stringResource(R.string.filament_refresh, slotName(slot)), onRefresh, enabled = refreshEnabled)
                }
            }
        }
    }
}

/** Posição em linha (tela estreita): marca, cor, nome com o material e a releitura do RFID. */
@Composable
private fun SlotRow(
    slot: Slot, filament: FilamentControls, checked: Boolean, idle: Boolean, refreshEnabled: Boolean, onToggle: (Boolean) -> Unit, onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val toggle = if (filament.loadUnload) Modifier.toggleable(checked, enabled = idle, role = Role.Checkbox, onValueChange = onToggle) else Modifier
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .background(if (checked) colors.surfaceContainerHigh else colors.surface, RoundedCornerShape(12.dp))
            .then(toggle).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (filament.loadUnload) Checkbox(checked = checked, onCheckedChange = null, enabled = idle, modifier = Modifier.size(28.dp))
        SlotSwatch(slot.takeIf { it.loaded }, 28.dp)
        Text(
            listOf(slotName(slot), if (slot.loaded) slot.type ?: "?" else stringResource(R.string.slot_empty)).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall, color = if (slot.loaded) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (filament.refresh) {
            AppIconButton(R.drawable.ic_contactless, stringResource(R.string.filament_refresh, slotName(slot)), onRefresh, enabled = refreshEnabled)
        }
    }
}

/**
 * Quantas posições estão marcadas e os botões que as põem na fila; na tela estreita os botões
 * dividem a largura e levam a contagem.
 */
@Composable
private fun SelectionBar(count: Int, idle: Boolean, wide: Boolean, onClear: () -> Unit, onQueue: (load: Boolean) -> Unit) {
    val enabled = idle && count > 0
    val hint = when {
        !idle -> stringResource(R.string.filament_busy)
        count == 0 -> stringResource(R.string.filament_select_hint)
        else -> stringResource(R.string.filament_queue_order)
    }
    if (wide) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                if (count > 0) Text(pluralStringResource(R.plurals.filament_selected, count, count), style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (count > 0) AppButton(stringResource(R.string.filament_deselect), onClear, style = ButtonStyle.TEXT)
            AppButton(stringResource(R.string.filament_unload), { onQueue(false) }, style = ButtonStyle.OUTLINED, icon = R.drawable.ic_eject, enabled = enabled)
            AppButton(stringResource(R.string.filament_load), { onQueue(true) }, icon = R.drawable.ic_download, enabled = enabled)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val unload = if (count > 0) stringResource(R.string.filament_unload_n, count) else stringResource(R.string.filament_unload)
                val load = if (count > 0) stringResource(R.string.filament_load_n, count) else stringResource(R.string.filament_load)
                AppButton(unload, { onQueue(false) }, Modifier.weight(1f), style = ButtonStyle.OUTLINED, icon = R.drawable.ic_eject, enabled = enabled)
                AppButton(load, { onQueue(true) }, Modifier.weight(1f), icon = R.drawable.ic_download, enabled = enabled)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A fila de filamento: cada operação com o estado (a em andamento com a barra), tirar as que não
 * começaram, tentar de novo a que falhou e limpar o que não está em andamento.
 */
@Composable
private fun FilamentQueue(host: PrinterHost, queue: List<FilamentTask>, viewModel: SlicerViewModel, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val running = queue.any { it.state == FilamentTask.State.RUNNING }
    val failed = queue.any { it.state == FilamentTask.State.FAILED }
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.filament_queue), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (queue.isNotEmpty()) {
                    Text("${queue.count { it.state == FilamentTask.State.DONE }} / ${queue.size}", style = MonoStyles.small, color = colors.onSurfaceVariant)
                }
                if (queue.isNotEmpty() && !running) AppButton(stringResource(R.string.filament_clear), { viewModel.clearFilamentQueue(host) }, style = ButtonStyle.TEXT)
            }
            HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (queue.isEmpty()) {
                    Text(
                        stringResource(R.string.filament_queue_empty), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                for (task in queue) QueueItem(task) { viewModel.cancelFilamentTask(host, task.id) }
                if (failed) {
                    AppButton(
                        stringResource(R.string.filament_resume), { viewModel.resumeFilamentQueue(host) }, Modifier.padding(4.dp),
                        style = ButtonStyle.TONAL, icon = R.drawable.ic_refresh,
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_info), null, Modifier.size(18.dp), tint = colors.outline)
                Text(
                    stringResource(R.string.filament_queue_info, (FILAMENT_TIMEOUT_MS / 60_000).toInt()),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QueueItem(task: FilamentTask, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = task.state == FilamentTask.State.RUNNING
    Column(
        Modifier.fillMaxWidth().background(if (active) colors.surfaceContainerHigh else colors.surfaceContainerLow, RoundedCornerShape(12.dp))
            .heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (icon, tint) = when (task.state) {
                FilamentTask.State.PENDING -> R.drawable.ic_schedule to colors.outline
                FilamentTask.State.RUNNING -> R.drawable.ic_progress_activity to colors.primary
                FilamentTask.State.DONE -> R.drawable.ic_check_circle to Tone.SUCCESS.color()
                FilamentTask.State.FAILED -> R.drawable.ic_error to Tone.ERROR.color()
            }
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (task.load) R.string.filament_task_load else R.string.filament_task_unload, slotName(task.slot)),
                    style = if (active) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
                Text(
                    task.error ?: stringResource(
                        when (task.state) {
                            FilamentTask.State.PENDING -> R.string.filament_state_pending
                            FilamentTask.State.RUNNING -> R.string.filament_state_running
                            FilamentTask.State.DONE -> R.string.filament_state_done
                            FilamentTask.State.FAILED -> R.string.filament_state_failed
                        },
                    ).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (task.state) {
                        FilamentTask.State.RUNNING -> colors.primary
                        FilamentTask.State.FAILED -> Tone.ERROR.color()
                        else -> colors.onSurfaceVariant
                    },
                )
            }
            if (task.state == FilamentTask.State.PENDING) AppIconButton(R.drawable.ic_close, stringResource(R.string.filament_cancel_task), onCancel)
        }
        if (active) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp).height(4.dp), strokeCap = StrokeCap.Round, gapSize = 0.dp)
    }
}

/**
 * Resumo do filamento na Visão geral: a cor e o material de cada posição, o andamento da fila e o
 * caminho para a aba Filamento ([onOpen]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilamentSummary(status: PrinterStatus?, slots: List<Slot>, queue: List<FilamentTask>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ContentCard(modifier, title = stringResource(R.string.filament), trailing = {
        if (queue.isNotEmpty()) {
            Text(
                "${stringResource(R.string.filament_queue)} ${queue.count { it.state == FilamentTask.State.DONE }}/${queue.size}",
                style = MonoStyles.small, color = MaterialTheme.colorScheme.primary,
            )
        }
    }) {
        for (box in status?.boxes.orEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(box.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                BoxReading(box)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in slots) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlotSwatch(slot.takeIf { it.loaded }, 16.dp)
                    Text(
                        if (slot.loaded) slot.type ?: "?" else "—", style = MonoStyles.small,
                        color = if (slot.loaded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AppButton(stringResource(R.string.filament_open), onOpen, style = ButtonStyle.TEXT, icon = R.drawable.ic_conveyor_belt)
    }
}

/** A partir daqui a fila fica ao lado das caixas. */
private val WIDE = 840.dp
/** A partir daqui cada posição é um cartão, quatro por linha. */
private val SLOT_CARDS = 560.dp
private val QUEUE_WIDTH = 360.dp
private const val SLOTS_PER_ROW = 4
