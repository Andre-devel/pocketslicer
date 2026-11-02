package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.Fan
import io.github.andredevel.slicer.send.Heater
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterControls
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.Temperature
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.NumberField
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Locale
import kotlin.math.roundToInt

/** Passos do jog em mm. */
private val JOG_STEPS = listOf(0.1f, 1f, 10f, 50f)

/**
 * Temperaturas de bico e mesa dos perfis genéricos do Orca
 * (`OrcaFilamentLibrary/filament/base/fdm_filament_*.json`: `nozzle_temperature`, `hot_plate_temp`).
 */
private val MATERIALS = listOf(
    Material("PLA", 220, 55), Material("PETG", 255, 80), Material("ABS", 260, 100),
    Material("ASA", 260, 100), Material("TPU", 240, 35), Material("PC", 280, 110),
)

private data class Material(val name: String, val nozzle: Int, val bed: Int)

/**
 * Aba Controle da tela da impressora: home, jog e motores (bloqueados durante a impressão),
 * alvo de cada aquecedor (com os valores dos materiais), ventoinhas e luzes do que a impressora
 * informa ter ([controls]). Durante a impressão, mudar temperatura, ventoinha ou luz pede
 * confirmação. Os valores atuais vêm do estado ao vivo ([status]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrinterControlTab(host: PrinterHost, status: PrinterStatus?, controls: PrinterControls, commands: CommandRunner) {
    val busy = status?.busy == true
    val ready = status != null && status.state != PrinterStatus.State.OFFLINE && !status.halted
    var confirm by remember(host) { mutableStateOf<PrinterCommand?>(null) }
    // Durante a impressão a mudança vale na hora para a peça: confirma antes.
    fun change(command: PrinterCommand) = if (busy) confirm = command else commands.run(command)

    if (controls.motion) Motion(status, ready && !busy && !commands.sending, busy, commands)
    if (controls.heaters.isNotEmpty()) {
        SectionLabel(stringResource(R.string.temperatures))
        for (heater in controls.heaters) HeaterRow(heater, status, ready && !commands.sending, ::change)
    }
    if (controls.fans.isNotEmpty()) {
        SectionLabel(stringResource(R.string.fans))
        for (fan in controls.fans) FanRow(fan, status, ready && !commands.sending, ::change)
    }
    if (controls.lights.isNotEmpty()) {
        SectionLabel(stringResource(R.string.lights))
        for (light in controls.lights) {
            LabeledRow(light.name) {
                Switch(
                    checked = status?.lights?.get(light.id) == true,
                    onCheckedChange = { change(PrinterCommand.SetLight(light.id, it)) },
                    enabled = ready && !commands.sending,
                )
            }
        }
    }
    confirm?.let { command ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.control_while_printing_title)) },
            text = { Text(stringResource(R.string.control_while_printing_text, host.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    commands.run(command)
                }) { Text(stringResource(R.string.control_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Posição, passo do jog, os botões de cada eixo, home e desligar motores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Motion(status: PrinterStatus?, enabled: Boolean, busy: Boolean, commands: CommandRunner) {
    SectionLabel(stringResource(R.string.motion))
    if (busy) Banner(Tone.INFO, stringResource(R.string.motion_locked), Modifier.fillMaxWidth(), icon = R.drawable.ic_lock)
    val homed = status?.homedAxes
    // Posição e aviso de home na mesma linha: o aviso some sem mudar o lugar dos botões.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        status?.position?.let { position ->
            Text(
                "XYZ".mapIndexed { i, axis -> "$axis %.2f".format(Locale.getDefault(), position[i]) }.joinToString("   "),
                style = MonoStyles.small,
            )
        }
        if (homed != null && homed.length < 3 && !busy) {
            Text(stringResource(R.string.not_homed), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    var step by rememberSaveable { mutableStateOf(1) }
    LabeledRow(stringResource(R.string.jog_step)) {
        SegmentedTabs(
            JOG_STEPS.map { Segment("%s mm".format(number(it))) }, step, { step = it },
            Modifier.widthIn(max = 320.dp), check = false, compact = true,
        )
    }
    // Sem o home do eixo o Klipper recusa o movimento; null = estado ainda sem toolhead.
    fun axisEnabled(axis: Char) = enabled && (homed == null || axis.lowercaseChar() in homed)
    @Composable
    fun jog(axis: Char, sign: Int) {
        AppButton(
            "$axis${if (sign > 0) "+" else "−"}",
            { commands.run(PrinterCommand.Jog(axis, sign * JOG_STEPS[step])) },
            Modifier.width(76.dp), style = ButtonStyle.OUTLINED, enabled = axisEnabled(axis),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            jog('Y', 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                jog('X', -1)
                Spacer(Modifier.size(76.dp, 0.dp))
                jog('X', 1)
            }
            jog('Y', -1)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            jog('Z', 1)
            jog('Z', -1)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton(stringResource(R.string.home_all), { commands.run(PrinterCommand.Home()) }, style = ButtonStyle.TONAL, enabled = enabled)
        for (axis in "XYZ") {
            AppButton(stringResource(R.string.home_axis, axis), { commands.run(PrinterCommand.Home("$axis")) }, style = ButtonStyle.OUTLINED, enabled = enabled)
        }
        AppButton(stringResource(R.string.motors_off), { commands.run(PrinterCommand.MotorsOff) }, style = ButtonStyle.OUTLINED, enabled = enabled)
    }
}

/** Temperatura atual, alvo digitado, desligar e os valores dos materiais (bico e mesa). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaterRow(heater: Heater, status: PrinterStatus?, enabled: Boolean, change: (PrinterCommand) -> Unit) {
    val temperatures = status?.temperatures.orEmpty()
    val temperature = temperatures.firstOrNull(heater::matches)
    val name = heaterName(heater, temperatures)
    fun set(target: Float) = change(PrinterCommand.SetTemperature(heater, heater.max?.let { target.coerceAtMost(it) } ?: target))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledRow(name, detail = temperature?.let { "%.0f °C".format(Locale.ROOT, it.current) }) {
            NumberField(
                temperature?.target ?: 0f, ::set, Modifier.width(112.dp),
                suffix = "°C", decimals = 0, min = 0f, enabled = enabled,
            )
            TextButton(onClick = { set(0f) }, enabled = enabled && (temperature?.target ?: 0f) > 0f) { Text(stringResource(R.string.heater_turn_off)) }
        }
        val materials = when (heater.source) {
            Temperature.Source.NOZZLE -> MATERIALS.map { it.name to it.nozzle }
            Temperature.Source.BED -> MATERIALS.map { it.name to it.bed }
            Temperature.Source.CHAMBER -> emptyList()
        }.filter { (_, value) -> heater.max == null || value <= heater.max }
        if (materials.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((material, value) in materials) {
                    AssistChip(onClick = { set(value.toFloat()) }, label = { Text("$material $value°") }, enabled = enabled)
                }
            }
        }
    }
}

/** Velocidade atual e um controle de 0 a 100%; o valor vai ao soltar. */
@Composable
private fun FanRow(fan: Fan, status: PrinterStatus?, enabled: Boolean, change: (PrinterCommand) -> Unit) {
    val current = status?.fans?.get(fan.id)
    var dragging by remember(fan.id) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: current ?: 0f
    val name = when (fan.kind) {
        Fan.Kind.PART -> stringResource(R.string.fan_part)
        Fan.Kind.AUXILIARY -> stringResource(R.string.fan_auxiliary)
        Fan.Kind.CHAMBER -> stringResource(R.string.chamber)
        Fan.Kind.OTHER -> fan.name
    }
    Column {
        LabeledRow(name) {
            Text(if (current == null && dragging == null) "—" else "${(shown * 100).roundToInt()}%", style = MonoStyles.small, modifier = Modifier.widthIn(min = 48.dp))
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { change(PrinterCommand.SetFan(fan.id, (it * 100).roundToInt() / 100f)) }
                dragging = null
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Bico", "Bico 2" (com vários), "Mesa", "Câmara". */
@Composable
private fun heaterName(heater: Heater, all: List<Temperature>): String = when (heater.source) {
    Temperature.Source.NOZZLE ->
        if (all.count { it.source == Temperature.Source.NOZZLE } > 1 || heater.index > 0) stringResource(R.string.nozzle_n, heater.index + 1)
        else stringResource(R.string.nozzle)
    Temperature.Source.BED -> stringResource(R.string.bed)
    Temperature.Source.CHAMBER -> stringResource(R.string.chamber)
}

private fun number(value: Float) = if (value % 1f == 0f) "%.0f".format(Locale.getDefault(), value) else "%.1f".format(Locale.getDefault(), value)
