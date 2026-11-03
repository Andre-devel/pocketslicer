package io.github.andredevel.slicer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andredevel.slicer.send.Fan
import io.github.andredevel.slicer.send.Heater
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterControls
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.Temperature
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.ContentCard
import io.github.andredevel.slicer.ui.components.NumberField
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
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

/** Largura a partir da qual Mover fica à esquerda e temperaturas, ventoinhas e luzes à direita. */
private val TWO_COLUMNS = 720.dp
private val MOVE_WIDTH = 520.dp
private val JOG_BUTTON = 64.dp

/**
 * Aba Controle da tela da impressora: Mover (home, jog e motores, bloqueados durante a impressão),
 * Temperaturas (alvo de cada aquecedor e os valores dos materiais para todos de uma vez) e
 * Ventoinhas e luz do que a impressora informa ter ([controls]). Na tela larga, Mover à esquerda e
 * o resto à direita. Durante a impressão, mudar temperatura, ventoinha ou luz pede confirmação.
 * Os valores atuais vêm do estado ao vivo ([status]).
 */
@Composable
internal fun PrinterControlTab(host: PrinterHost, status: PrinterStatus?, controls: PrinterControls, commands: CommandRunner) {
    val busy = status?.busy == true
    val ready = status != null && status.state != PrinterStatus.State.OFFLINE && !status.halted
    val enabled = ready && !commands.sending
    var confirm by remember(host) { mutableStateOf<List<PrinterCommand>?>(null) }
    // Durante a impressão a mudança vale na hora para a peça: confirma antes.
    fun change(changes: List<PrinterCommand>) = if (busy) confirm = changes else commands.run(changes)

    val side: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (controls.heaters.isNotEmpty()) Temperatures(controls.heaters, status, enabled, ::change)
            if (controls.fans.isNotEmpty() || controls.lights.isNotEmpty()) FansAndLights(controls, status, enabled, ::change)
        }
    }
    val hasSide = controls.heaters.isNotEmpty() || controls.fans.isNotEmpty() || controls.lights.isNotEmpty()
    BoxWithConstraints {
        val wide = maxWidth >= TWO_COLUMNS
        val moveWidth = MOVE_WIDTH.coerceAtMost(maxWidth * 0.45f)
        when {
            controls.motion && hasSide && wide -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Move(status, enabled && !busy, Modifier.width(moveWidth), commands)
                side(Modifier.weight(1f))
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (controls.motion) Move(status, enabled && !busy, Modifier.fillMaxWidth(), commands)
                if (hasSide) side(Modifier.fillMaxWidth())
            }
        }
    }
    confirm?.let { changes ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.control_while_printing_title)) },
            text = { Text(stringResource(R.string.control_while_printing_text, host.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    commands.run(changes)
                }) { Text(stringResource(R.string.control_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Posição, passo do jog, a cruz de X e Y com o home no meio, Z ao lado, home de todos e motores. */
@Composable
private fun Move(status: PrinterStatus?, enabled: Boolean, modifier: Modifier, commands: CommandRunner) {
    val colors = MaterialTheme.colorScheme
    val position = status?.position
    ContentCard(
        modifier, title = stringResource(R.string.motion),
        trailing = {
            if (position != null) {
                Text(
                    buildAnnotatedString {
                        "XYZ".forEachIndexed { i, axis ->
                            if (i > 0) append("   ")
                            append("$axis ")
                            withStyle(SpanStyle(color = colors.onSurface)) { append("%.2f".format(Locale.getDefault(), position[i])) }
                        }
                    },
                    style = MonoStyles.small, color = colors.onSurfaceVariant,
                )
            }
        },
    ) {
        var step by rememberSaveable { mutableStateOf(2) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.jog_step), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            SegmentedTabs(
                JOG_STEPS.mapIndexed { i, it -> Segment(number(it) + if (i == JOG_STEPS.lastIndex) " mm" else "") }, step, { step = it },
                Modifier.fillMaxWidth(), check = false, compact = true,
            )
        }
        val homed = status?.homedAxes
        // Sem o home do eixo o Klipper recusa o movimento; null = estado ainda sem toolhead.
        fun axisEnabled(axis: Char) = enabled && (homed == null || axis.lowercaseChar() in homed)
        @Composable
        fun jog(axis: Char, sign: Int, @DrawableRes icon: Int) {
            val label = "$axis${if (sign > 0) "+" else "−"}"
            JogButton(icon, label, label, axisEnabled(axis)) { commands.run(PrinterCommand.Jog(axis, sign * JOG_STEPS[step])) }
        }
        @Composable
        fun home(axes: String) = JogButton(
            R.drawable.ic_home, axes, stringResource(R.string.home_axis, axes), enabled, home = true,
        ) { commands.run(PrinterCommand.Home(axes)) }

        val gap = Arrangement.spacedBy(8.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
            Column(verticalArrangement = gap, horizontalAlignment = Alignment.CenterHorizontally) {
                jog('Y', 1, R.drawable.ic_keyboard_arrow_up)
                Row(horizontalArrangement = gap) {
                    jog('X', -1, R.drawable.ic_keyboard_arrow_left)
                    home("XY")
                    jog('X', 1, R.drawable.ic_keyboard_arrow_right)
                }
                jog('Y', -1, R.drawable.ic_keyboard_arrow_down)
            }
            Column(verticalArrangement = gap) {
                jog('Z', 1, R.drawable.ic_keyboard_arrow_up)
                home("Z")
                jog('Z', -1, R.drawable.ic_keyboard_arrow_down)
            }
        }
        Row(horizontalArrangement = gap) {
            AppButton(stringResource(R.string.home_all), { commands.run(PrinterCommand.Home()) }, Modifier.weight(1f), style = ButtonStyle.TONAL, icon = R.drawable.ic_home, enabled = enabled)
            AppButton(
                stringResource(R.string.motors_off), { commands.run(PrinterCommand.MotorsOff) }, Modifier.weight(1f),
                style = ButtonStyle.OUTLINED, icon = R.drawable.ic_power_settings_new, enabled = enabled,
            )
        }
        Note(stringResource(R.string.motion_note))
    }
}

/** Botão quadrado do jog: ícone e o eixo embaixo; o home com borda e o ícone na cor de destaque. */
@Composable
private fun JogButton(@DrawableRes icon: Int, label: String, description: String, enabled: Boolean, home: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.size(JOG_BUTTON).semantics { contentDescription = description }.alpha(if (enabled) 1f else 0.38f),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (home) colors.surface else colors.surfaceContainerHighest,
        border = if (home) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), null, Modifier.size(if (home) 24.dp else 26.dp), tint = if (home) colors.primary else colors.onSurface)
            Text(label, style = MonoStyles.small.copy(fontSize = 11.sp), color = colors.onSurfaceVariant)
        }
    }
}

/**
 * Um bloco por aquecedor (atual, alvo digitado e o máximo) e, no topo, os materiais: um toque põe
 * bico e mesa nos valores dele; Desligar zera todos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Temperatures(heaters: List<Heater>, status: PrinterStatus?, enabled: Boolean, change: (List<PrinterCommand>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val temperatures = status?.temperatures.orEmpty()
    fun target(heater: Heater, value: Float) = PrinterCommand.SetTemperature(heater, heater.max?.let { value.coerceAtMost(it) } ?: value)
    ContentCard {
        FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.temperatures), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.material_presets), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            for (material in MATERIALS) {
                val changes = heaters.mapNotNull { heater ->
                    when (heater.source) {
                        Temperature.Source.NOZZLE -> material.nozzle
                        Temperature.Source.BED -> material.bed
                        Temperature.Source.CHAMBER -> null
                    }?.takeIf { heater.max == null || it <= heater.max }?.let { target(heater, it.toFloat()) }
                }
                if (changes.isNotEmpty()) Chip(material.name, enabled) { change(changes) }
            }
            Chip(stringResource(R.string.heater_turn_off), enabled && temperatures.any { (it.target ?: 0f) > 0f }) {
                change(heaters.map { target(it, 0f) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 3) {
            for (heater in heaters) HeaterTile(heater, temperatures, enabled, Modifier.weight(1f)) { change(listOf(target(heater, it))) }
            repeat((3 - heaters.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Chip(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick, enabled = enabled, shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(36.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) }
}

/** Nome com ícone, temperatura atual grande, o alvo e o máximo da impressora. */
@Composable
private fun HeaterTile(heater: Heater, all: List<Temperature>, enabled: Boolean, modifier: Modifier, onTarget: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val temperature = all.firstOrNull(heater::matches)
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerLowest) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val icon = when (heater.source) {
                    Temperature.Source.NOZZLE -> R.drawable.ic_thermostat
                    Temperature.Source.BED -> R.drawable.ic_heat
                    Temperature.Source.CHAMBER -> R.drawable.ic_sensor_door
                }
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Text(heaterName(heater, all), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
            }
            Text(temperature?.let { "%.0f °C".format(Locale.ROOT, it.current) } ?: "—", style = MonoStyles.title)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.heater_target), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                NumberField(temperature?.target ?: 0f, onTarget, Modifier.fillMaxWidth(), suffix = "°C", decimals = 0, min = 0f, enabled = enabled)
            }
            heater.max?.let { Text(stringResource(R.string.heater_max, "%.0f".format(Locale.ROOT, it)), style = MaterialTheme.typography.labelSmall, color = colors.outline) }
        }
    }
}

/** Cada ventoinha com nome, controle de 0 a 100% e o valor; embaixo as luzes. */
@Composable
private fun FansAndLights(controls: PrinterControls, status: PrinterStatus?, enabled: Boolean, change: (List<PrinterCommand>) -> Unit) {
    val title = when {
        controls.fans.isEmpty() -> R.string.lights
        controls.lights.isEmpty() -> R.string.fans
        else -> R.string.fans_and_lights
    }
    ContentCard(title = stringResource(title)) {
        BoxWithConstraints {
            val labelWidth = if (maxWidth >= 480.dp) 140.dp else 88.dp
            Column {
                for (fan in controls.fans) FanRow(fan, status, enabled, labelWidth) { change(listOf(it)) }
            }
        }
        if (controls.fans.isNotEmpty() && controls.lights.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        for (light in controls.lights) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_lightbulb), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(light.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = status?.lights?.get(light.id) == true,
                    onCheckedChange = { change(listOf(PrinterCommand.SetLight(light.id, it))) },
                    enabled = enabled,
                )
            }
        }
        Note(stringResource(R.string.control_confirm_note))
    }
}

/** Nome, controle e valor numa linha; o valor vai ao soltar. */
@Composable
private fun FanRow(fan: Fan, status: PrinterStatus?, enabled: Boolean, labelWidth: Dp, change: (PrinterCommand) -> Unit) {
    val current = status?.fans?.get(fan.id)
    var dragging by remember(fan.id) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: current ?: 0f
    val name = when (fan.kind) {
        Fan.Kind.PART -> stringResource(R.string.fan_part)
        Fan.Kind.AUXILIARY -> stringResource(R.string.fan_auxiliary)
        Fan.Kind.CHAMBER -> stringResource(R.string.chamber)
        Fan.Kind.OTHER -> fan.name
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(labelWidth), maxLines = 2)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { change(PrinterCommand.SetFan(fan.id, (it * 100).roundToInt() / 100f)) }
                dragging = null
            },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (current == null && dragging == null) "—" else "${(shown * 100).roundToInt()}%",
            style = MonoStyles.small, modifier = Modifier.width(48.dp), textAlign = TextAlign.End,
        )
    }
}

/** Texto pequeno de rodapé de um bloco. */
@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
