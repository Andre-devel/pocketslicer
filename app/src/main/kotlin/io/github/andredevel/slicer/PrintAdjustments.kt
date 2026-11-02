package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.Adjustments
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.CommitField
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.NumberField
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import java.util.Locale
import kotlin.math.roundToInt

/** Passos do babystep Z em mm. */
private val Z_STEPS = listOf(-0.05f, -0.01f, 0.01f, 0.05f)

/**
 * Ajustes da impressão em andamento, na Visão geral: velocidade e fluxo em %, modo de impressão
 * (Creality), babystep Z e pausar numa camada, o que [adjustments] diz que a impressora tem. Valem
 * na hora, sem confirmação: mudar a impressão em curso é o propósito deles. Os valores atuais vêm
 * do estado ao vivo ([status]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrintAdjustments(status: PrinterStatus, adjustments: Adjustments, commands: CommandRunner) {
    val enabled = !status.halted && status.state != PrinterStatus.State.OFFLINE && !commands.sending
    SectionLabel(stringResource(R.string.print_adjustments))
    if (adjustments.speed) PercentRow(stringResource(R.string.print_speed), status.speed, enabled) { commands.run(PrinterCommand.SetSpeed(it)) }
    if (adjustments.flow) PercentRow(stringResource(R.string.print_flow), status.flow, enabled) { commands.run(PrinterCommand.SetFlow(it)) }
    if (adjustments.printModes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.print_mode), style = MaterialTheme.typography.bodyMedium)
            SegmentedTabs(
                adjustments.printModes.map { Segment(printModeName(it), enabled = enabled) },
                adjustments.printModes.indexOf(status.printMode),
                { commands.run(PrinterCommand.SetPrintMode(adjustments.printModes[it])) },
                Modifier.widthIn(max = 480.dp).fillMaxWidth(), check = false, compact = true,
            )
        }
    }
    if (adjustments.zOffset) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledRow(stringResource(R.string.z_offset), detail = status.zOffset?.let { "%.3f mm".format(Locale.getDefault(), it) }) {}
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in Z_STEPS) {
                    AppButton(
                        "%+.2f".format(Locale.getDefault(), step), { commands.run(PrinterCommand.AdjustZOffset(step)) },
                        style = ButtonStyle.OUTLINED, enabled = enabled,
                    )
                }
            }
        }
    }
    if (adjustments.pauseAtLayer) {
        val current = status.layer ?: 0
        // A camada digitada que não vale (já começou ou não existe), até a próxima tentativa.
        var invalid by remember { mutableStateOf(false) }
        LabeledRow(
            stringResource(R.string.pause_at_layer),
            detail = when {
                invalid -> status.layers?.let { stringResource(R.string.pause_at_layer_range, current + 1, it) }
                    ?: stringResource(R.string.pause_at_layer_after, current)
                else -> status.pauseAtLayer?.let { stringResource(R.string.pause_at_layer_set, it) }
            },
        ) {
            // Vazio sem pausa marcada; só uma camada que ainda não começou (e que existe) vale.
            CommitField(
                status.pauseAtLayer?.toString().orEmpty(),
                { text ->
                    val layer = text.trim().toIntOrNull()?.takeIf { it > current && (status.layers == null || it <= status.layers) }
                    invalid = layer == null && text.isNotBlank()
                    layer?.let { commands.run(PrinterCommand.PauseAtLayer(it)) }
                },
                Modifier.width(96.dp), enabled = enabled, keyboardType = KeyboardType.Number,
            )
            TextButton(onClick = { commands.run(PrinterCommand.PauseAtLayer(null)) }, enabled = enabled && status.pauseAtLayer != null) {
                Text(stringResource(R.string.pause_at_layer_clear))
            }
        }
    }
}

/** Valor em % digitado e a volta a 100%. */
@Composable
private fun PercentRow(label: String, value: Int?, enabled: Boolean, set: (Int) -> Unit) {
    LabeledRow(label) {
        NumberField(value?.toFloat() ?: 100f, { set(it.roundToInt()) }, Modifier.width(112.dp), suffix = "%", decimals = 0, min = 1f, enabled = enabled && value != null)
        TextButton(onClick = { set(100) }, enabled = enabled && value != null && value != 100) { Text("100%") }
    }
}

/** Nome do modo do `SET_PRINT_MODE`; o do firmware quando o app não conhece. */
@Composable
private fun printModeName(mode: String): String = when (mode) {
    "standard" -> stringResource(R.string.print_mode_standard)
    "extreme" -> stringResource(R.string.print_mode_extreme)
    "smooth" -> stringResource(R.string.print_mode_smooth)
    "silent" -> stringResource(R.string.print_mode_silent)
    else -> mode
}
