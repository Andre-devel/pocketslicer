package io.github.andredevel.slicer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.Maintenance
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Tone
import kotlinx.coroutines.delay

/**
 * Aba Manutenção da tela da impressora: as calibrações e a limpeza do bico que ela tem
 * ([tasks]), uma por vez, com confirmação (e o aviso de mesa livre quando o bico desce nela).
 * Só com a impressora livre, sem outra calibração ([run]) nem operação de filamento
 * ([filamentRunning]) em andamento. A calibração em andamento mostra há quanto tempo roda; a
 * parada de emergência do cabeçalho segue disponível.
 */
@Composable
internal fun PrinterMaintenanceTab(
    host: PrinterHost, status: PrinterStatus?, tasks: List<Maintenance>, run: MaintenanceRun?, filamentRunning: Boolean, viewModel: SlicerViewModel,
) {
    val running = run?.state == MaintenanceRun.State.RUNNING
    val ready = status != null && (status.state == PrinterStatus.State.IDLE || status.state == PrinterStatus.State.ERROR) && !status.halted && !running && !filamentRunning
    var confirm by remember(host) { mutableStateOf<Maintenance?>(null) }

    if (run != null) RunBanner(run) { viewModel.dismissMaintenance(host) }
    if (status?.busy == true) Banner(Tone.INFO, stringResource(R.string.maintenance_busy), Modifier.widthIn(max = 720.dp).fillMaxWidth())
    SectionLabel(stringResource(R.string.maintenance_calibration))
    for (task in tasks) {
        LabeledRow(stringResource(task.label), Modifier.widthIn(max = 720.dp), detail = stringResource(task.detail)) {
            AppButton(stringResource(R.string.maintenance_start), { confirm = task }, style = ButtonStyle.TONAL, icon = R.drawable.ic_play_arrow, enabled = ready)
        }
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

/** A calibração em andamento (com o tempo, a cada segundo) ou como a última terminou. */
@Composable
private fun RunBanner(run: MaintenanceRun, onDismiss: () -> Unit) {
    val name = stringResource(run.task.label)
    val modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
    when (run.state) {
        MaintenanceRun.State.RUNNING -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(run) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            Banner(Tone.INFO, stringResource(R.string.maintenance_running_text, elapsed(now - run.startedAt)), modifier,
                title = stringResource(R.string.maintenance_running, name), icon = R.drawable.ic_progress_activity)
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

@get:StringRes
private val Maintenance.detail get() = when (this) {
    Maintenance.BED_LEVELING -> R.string.maintenance_bed_leveling_detail
    Maintenance.Z_AXIS -> R.string.maintenance_z_axis_detail
    Maintenance.INPUT_SHAPING -> R.string.maintenance_input_shaping_detail
    Maintenance.NOZZLE_PID -> R.string.maintenance_nozzle_pid_detail
    Maintenance.BED_PID -> R.string.maintenance_bed_pid_detail
    Maintenance.NOZZLE_CLEAN -> R.string.maintenance_nozzle_clean_detail
}
