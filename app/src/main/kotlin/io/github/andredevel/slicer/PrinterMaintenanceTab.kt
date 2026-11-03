package io.github.andredevel.slicer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.Maintenance
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import kotlinx.coroutines.delay

/**
 * Aba Manutenção da tela da impressora: as calibrações e a limpeza do bico que ela tem
 * ([tasks]), em cartões, uma por vez, com confirmação (e o aviso de mesa livre quando o bico
 * desce nela). Só com a impressora livre, sem outra calibração ([run]) nem operação de filamento
 * ([filamentRunning]) em andamento. A calibração em andamento aparece no topo com há quanto tempo
 * roda e no próprio cartão; a parada de emergência do cabeçalho segue disponível.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrinterMaintenanceTab(
    host: PrinterHost, status: PrinterStatus?, tasks: List<Maintenance>, run: MaintenanceRun?, filamentRunning: Boolean, viewModel: SlicerViewModel,
) {
    val running = run?.state == MaintenanceRun.State.RUNNING
    val ready = status != null && (status.state == PrinterStatus.State.IDLE || status.state == PrinterStatus.State.ERROR) && !status.halted && !running && !filamentRunning
    var confirm by remember(host) { mutableStateOf<Maintenance?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (run != null) RunCard(run) { viewModel.dismissMaintenance(host) }
        if (status?.busy == true) Banner(Tone.INFO, stringResource(R.string.maintenance_busy), Modifier.fillMaxWidth())
        BoxWithConstraints {
            val columns = when {
                maxWidth >= 900.dp -> 3
                maxWidth >= 560.dp -> 2
                else -> 1
            }
            // Cada linha da altura do cartão mais alto, com os botões alinhados no pé.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in tasks.chunked(columns)) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (task in row) {
                            val state = when {
                                run?.task == task && running -> TaskState.RUNNING
                                running || filamentRunning -> TaskState.ONE_AT_A_TIME
                                else -> TaskState.IDLE
                            }
                            TaskCard(task, state, ready, Modifier.weight(1f).fillMaxHeight()) { confirm = task }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.maintenance_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }

    confirm?.let { task ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.maintenance_confirm_title, stringResource(task.label))) },
            text = {
                val parts = listOfNotNull(
                    stringResource(task.detail),
                    stringResource(R.string.maintenance_confirm_bed).takeIf { task.bed },
                    stringResource(R.string.maintenance_confirm_stop),
                )
                Text(parts.joinToString("\n\n"))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    viewModel.runMaintenance(host, task)
                }) { Text(stringResource(R.string.maintenance_start)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private enum class TaskState { IDLE, RUNNING, ONE_AT_A_TIME }

/** Cartão de uma calibração: ícone e nome, o que faz, os avisos e o botão; borda de destaque enquanto roda. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(task: Maintenance, state: TaskState, ready: Boolean, modifier: Modifier, onStart: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = state == TaskState.RUNNING
    Surface(
        modifier, shape = RoundedCornerShape(16.dp), color = colors.surface,
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) colors.primary else colors.outlineVariant),
    ) {
        // O botão no pé do cartão, alinhado com os vizinhos da mesma linha.
        Column(Modifier.padding(16.dp)) {
          Column(Modifier.weight(1f).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(task.icon), null, Modifier.size(24.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
                Text(stringResource(task.label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(stringResource(task.detail), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (active) Tag(stringResource(R.string.maintenance_tag_running), colors.primary)
                if (task.bed) Tag(stringResource(R.string.maintenance_tag_bed), Tone.WARNING.color())
                if (task == Maintenance.NOZZLE_PID || task == Maintenance.BED_PID) Tag(stringResource(R.string.maintenance_tag_restart), colors.onSurfaceVariant)
            }
          }
            when (state) {
                TaskState.RUNNING -> AppButton(stringResource(R.string.maintenance_running_button), {}, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINED, enabled = false)
                TaskState.ONE_AT_A_TIME -> AppButton(stringResource(R.string.maintenance_one_at_a_time), {}, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL, enabled = false)
                TaskState.IDLE -> AppButton(stringResource(R.string.maintenance_start), onStart, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL, icon = R.drawable.ic_play_arrow, enabled = ready)
            }
        }
    }
}

/** Aviso pequeno com borda na cor dele. */
@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A calibração em andamento (com o tempo, a cada segundo, e uma barra) ou como a última terminou. */
@Composable
private fun RunCard(run: MaintenanceRun, onDismiss: () -> Unit) {
    val name = stringResource(run.task.label)
    val modifier = Modifier.fillMaxWidth()
    when (run.state) {
        MaintenanceRun.State.RUNNING -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(run) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            val colors = MaterialTheme.colorScheme
            Surface(modifier, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.outlineVariant)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(48.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(run.task.icon), null, Modifier.size(26.dp), tint = colors.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.maintenance_running, name), style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(Modifier.widthIn(max = 520.dp).fillMaxWidth(), strokeCap = StrokeCap.Round, gapSize = 0.dp)
                        Text(
                            stringResource(R.string.maintenance_running_text, elapsed(now - run.startedAt)),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        MaintenanceRun.State.DONE -> Banner(
            Tone.SUCCESS, stringResource(R.string.maintenance_done_text, elapsed((run.endedAt ?: run.startedAt) - run.startedAt)), modifier,
            title = stringResource(R.string.maintenance_done, name), onDismiss = onDismiss,
        )
        MaintenanceRun.State.FAILED -> Banner(
            Tone.ERROR, run.error.orEmpty(), modifier, title = stringResource(R.string.maintenance_failed, name), onDismiss = onDismiss,
        )
    }
}

/** "12 min 05 s". */
private fun elapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d min %02d s".format(seconds / 60, seconds % 60)
}

@get:StringRes
private val Maintenance.label get() = when (this) {
    Maintenance.BED_LEVELING -> R.string.maintenance_bed_leveling
    Maintenance.Z_AXIS -> R.string.maintenance_z_axis
    Maintenance.INPUT_SHAPING -> R.string.maintenance_input_shaping
    Maintenance.NOZZLE_PID -> R.string.maintenance_nozzle_pid
    Maintenance.BED_PID -> R.string.maintenance_bed_pid
    Maintenance.NOZZLE_CLEAN -> R.string.maintenance_nozzle_clean
}

@get:DrawableRes
private val Maintenance.icon get() = when (this) {
    Maintenance.BED_LEVELING -> R.drawable.ic_grid_on
    Maintenance.Z_AXIS -> R.drawable.ic_height
    Maintenance.INPUT_SHAPING -> R.drawable.ic_graphic_eq
    Maintenance.NOZZLE_PID -> R.drawable.ic_thermostat
    Maintenance.BED_PID -> R.drawable.ic_heat
    Maintenance.NOZZLE_CLEAN -> R.drawable.ic_cleaning_services
}

@get:StringRes
private val Maintenance.detail get() = when (this) {
    Maintenance.BED_LEVELING -> R.string.maintenance_bed_leveling_detail
    Maintenance.Z_AXIS -> R.string.maintenance_z_axis_detail
    Maintenance.INPUT_SHAPING -> R.string.maintenance_input_shaping_detail
    Maintenance.NOZZLE_PID -> R.string.maintenance_nozzle_pid_detail
    Maintenance.BED_PID -> R.string.maintenance_bed_pid_detail
    Maintenance.NOZZLE_CLEAN -> R.string.maintenance_nozzle_clean_detail
}
