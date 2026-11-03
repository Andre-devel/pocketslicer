package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.FilamentControls
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.Slot
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles

/**
 * As posições de filamento de [host] ([slots]). Com [FilamentControls.loadUnload], cada uma
 * tem uma marca: o usuário marca várias e toca uma vez em Carregar ou Descarregar, e a fila do
 * ViewModel ([queue]) faz uma por vez; a fila aparece embaixo, com o que falta, o que terminou e
 * o erro que a parou. Com [FilamentControls.refresh], cada posição relê o RFID. Só com a
 * impressora livre.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrinterFilament(
    host: PrinterHost, status: PrinterStatus?, slots: List<Slot>, filament: FilamentControls, queue: List<FilamentTask>,
    viewModel: SlicerViewModel, commands: CommandRunner,
) {
    val idle = status?.state == PrinterStatus.State.IDLE && !status.halted
    val queueRunning = queue.any { it.state == FilamentTask.State.RUNNING }
    var selected by remember(host) { mutableStateOf(emptySet<Int>()) }
    for (slot in slots) {
        val checked = slot.index in selected
        val row = if (filament.loadUnload) {
            Modifier.toggleable(checked, enabled = idle, role = Role.Checkbox) { selected = if (it) selected + slot.index else selected - slot.index }
        } else Modifier
        Row(row.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filament.loadUnload) Checkbox(checked = checked, onCheckedChange = null, enabled = idle)
            SlotSwatch(slot.takeIf { it.loaded })
            Text(slotName(slot), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (slot.loaded) slot.type ?: "?" else stringResource(R.string.slot_empty),
                style = MonoStyles.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filament.refresh) {
                AppIconButton(
                    R.drawable.ic_refresh, stringResource(R.string.filament_refresh, slotName(slot)),
                    { commands.run(PrinterCommand.RefreshSlot(slot)) }, enabled = idle && !queueRunning && !commands.sending,
                )
            }
        }
    }
    if (filament.loadUnload && slots.isNotEmpty()) {
        val chosen = slots.filter { it.index in selected }
        fun queueChosen(load: Boolean) {
            viewModel.queueFilament(host, chosen, load)
            selected = emptySet()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(stringResource(R.string.filament_load), { queueChosen(true) }, icon = R.drawable.ic_download, enabled = idle && chosen.isNotEmpty())
            AppButton(stringResource(R.string.filament_unload), { queueChosen(false) }, style = ButtonStyle.TONAL, icon = R.drawable.ic_upload, enabled = idle && chosen.isNotEmpty())
        }
        if (chosen.isEmpty() && queue.isEmpty()) {
            Text(stringResource(R.string.filament_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (queue.isNotEmpty()) FilamentQueue(host, queue, viewModel)
}

/** A fila de filamento: cada operação com o estado; tirar as que não começaram, tentar de novo a que falhou. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilamentQueue(host: PrinterHost, queue: List<FilamentTask>, viewModel: SlicerViewModel) {
    Text(stringResource(R.string.filament_queue), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    for (task in queue) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SlotSwatch(task.slot.takeIf { it.loaded })
            Column(Modifier.weight(1f)) {
                val name = slotName(task.slot)
                Text(
                    stringResource(if (task.load) R.string.filament_task_load else R.string.filament_task_unload, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.ERROR.color()) }
            }
            Text(
                stringResource(
                    when (task.state) {
                        FilamentTask.State.PENDING -> R.string.filament_state_pending
                        FilamentTask.State.RUNNING -> R.string.filament_state_running
                        FilamentTask.State.DONE -> R.string.filament_state_done
                        FilamentTask.State.FAILED -> R.string.filament_state_failed
                    },
                ),
                style = MonoStyles.small,
                color = when (task.state) {
                    FilamentTask.State.RUNNING -> MaterialTheme.colorScheme.primary
                    FilamentTask.State.FAILED -> Tone.ERROR.color()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (task.state == FilamentTask.State.PENDING) {
                AppIconButton(R.drawable.ic_close, stringResource(R.string.filament_cancel_task), { viewModel.cancelFilamentTask(host, task.id) })
            }
        }
    }
    val failed = queue.any { it.state == FilamentTask.State.FAILED }
    val running = queue.any { it.state == FilamentTask.State.RUNNING }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (failed) AppButton(stringResource(R.string.filament_resume), { viewModel.resumeFilamentQueue(host) }, style = ButtonStyle.TONAL, icon = R.drawable.ic_refresh)
        if (!running) AppButton(stringResource(R.string.filament_clear), { viewModel.clearFilamentQueue(host) }, style = ButtonStyle.OUTLINED)
    }
}
