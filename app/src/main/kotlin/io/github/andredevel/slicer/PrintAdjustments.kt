package io.github.andredevel.slicer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.github.andredevel.slicer.ui.components.ContentCard
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.StepperField
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Locale

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
internal fun PrintAdjustments(status: PrinterStatus, adjustments: Adjustments, commands: CommandRunner, modifier: Modifier = Modifier) {
    val enabled = !status.halted && status.state != PrinterStatus.State.OFFLINE && !commands.sending
    ContentCard(modifier, title = stringResource(R.string.print_adjustments), subtitle = stringResource(R.string.print_adjustments_hint)) {
    if (adjustments.speed) PercentRow(stringResource(R.string.print_speed), status.speed, enabled) { commands.run(PrinterCommand.SetSpeed(it)) }
    if (adjustments.flow) PercentRow(stringResource(R.string.print_flow), status.flow, enabled) { commands.run(PrinterCommand.SetFlow(it)) }
    if (adjustments.printModes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.print_mode), style = MaterialTheme.typography.bodyMedium)
            // Dois por linha: os nomes não cabem quatro lado a lado na coluna do tablet.
            FlowRow(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                for (mode in adjustments.printModes) {
                    AppButton(
                        printModeName(mode), { if (mode != status.printMode) commands.run(PrinterCommand.SetPrintMode(mode)) }, Modifier.weight(1f),
                        style = if (mode == status.printMode) ButtonStyle.TONAL else ButtonStyle.OUTLINED, enabled = enabled,
                    )
                }
            }
        }
    }
    if (adjustments.zOffset) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledRow(stringResource(R.string.z_offset), detail = status.zOffset?.let { "%.3f mm".format(Locale.getDefault(), it) }) {}
            // Os quatro numa linha, com pouca margem: cabem na coluna do tablet e no celular.
            Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (step in Z_STEPS) {
                    OutlinedButton(
                        { commands.run(PrinterCommand.AdjustZOffset(step)) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Text("%+.2f".format(Locale.getDefault(), step), style = MonoStyles.value, maxLines = 1) }
                }
            }
        }
    }
    if (adjustments.pauseAtLayer) {
        val current = status.layer ?: 0
        // A camada digitada que não vale (já começou ou não existe), até a próxima tentativa.
        var invalid by remember { mutableStateOf(false) }
        val detail = when {
            invalid -> status.layers?.let { stringResource(R.string.pause_at_layer_range, current + 1, it) }
                ?: stringResource(R.string.pause_at_layer_after, current)
            else -> status.pauseAtLayer?.let { stringResource(R.string.pause_at_layer_set, it) }
        }
        Text(stringResource(R.string.pause_at_layer), style = MaterialTheme.typography.bodyMedium)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Vazio sem pausa marcada; só uma camada que ainda não começou (e que existe) vale.
            CommitField(
                status.pauseAtLayer?.toString().orEmpty(),
                { text ->
                    val layer = text.trim().toIntOrNull()?.takeIf { it > current && (status.layers == null || it <= status.layers) }
                    invalid = layer == null && text.isNotBlank()
                    layer?.let { commands.run(PrinterCommand.PauseAtLayer(it)) }
                },
                Modifier.weight(1f).widthIn(max = 200.dp), enabled = enabled, keyboardType = KeyboardType.Number,
            )
            TextButton(onClick = { commands.run(PrinterCommand.PauseAtLayer(null)) }, enabled = enabled && status.pauseAtLayer != null) {
                Text(stringResource(R.string.pause_at_layer_clear))
            }
        }
    }
    }
}

/** Valor em %: − e + de 5 em 5 ou digitado, e a volta a 100%. */
@Composable
private fun PercentRow(label: String, value: Int?, enabled: Boolean, set: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepperField(value ?: 100, set, Modifier.weight(1f).widthIn(max = 280.dp), min = 1, enabled = enabled && value != null, step = PERCENT_STEP, suffix = "%")
            TextButton(onClick = { set(100) }, enabled = enabled && value != null && value != 100) { Text("100%") }
        }
    }
}

private const val PERCENT_STEP = 5

/** Nome do modo do `SET_PRINT_MODE`; o do firmware quando o app não conhece. */
@Composable
private fun printModeName(mode: String): String = when (mode) {
    "standard" -> stringResource(R.string.print_mode_standard)
    "extreme" -> stringResource(R.string.print_mode_extreme)
    "smooth" -> stringResource(R.string.print_mode_smooth)
    "silent" -> stringResource(R.string.print_mode_silent)
    else -> mode
}
