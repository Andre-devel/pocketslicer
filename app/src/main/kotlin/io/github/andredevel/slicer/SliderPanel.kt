package io.github.andredevel.slicer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import android.util.Log
import io.github.andredevel.slicer.send.SendException
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.render.PreviewSettings
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.CommitField
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.StepperField
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.theme.MonoStyles
import io.github.andredevel.slicer.ui.theme.PocketTheme
import java.util.Locale
import kotlin.math.abs

/**
 * Aba Slider do Visualizar: curso do carrinho, modo do pan, montagem do trilho e o que o
 * slider faz ao longo da impressão, na camada [layer] do seletor de camadas.
 */
@Composable
fun SliderPanel(
    plan: SliderPlan,
    targets: List<SliderTarget>,
    layer: Int,
    onConfig: (SliderConfig) -> Unit,
    parkMs: Int,
    onParkMs: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = plan.config
    val travel = SliderGeometry.TRAVEL
    val limit = SliderGeometry.PAN_LIMIT
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SliderEspStatus(config.espHost)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.slider_carriage))
            RailBar(plan, layer)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledStepper(stringResource(R.string.slider_start), config.start, { onConfig(config.copy(start = it)) }, 0, travel, 10, "mm", Modifier.weight(1f))
                LabeledStepper(stringResource(R.string.slider_end), config.end, { onConfig(config.copy(end = it)) }, 0, travel, 10, "mm", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val passes = SliderGeometry.PASSES_RANGE
                val dwell = SliderGeometry.DWELL_RANGE
                LabeledStepper(stringResource(R.string.slider_passes), config.passes, { onConfig(config.copy(passes = it)) }, passes.first, passes.last, 1, "", Modifier.weight(1f))
                if (config.passes > 0) {
                    LabeledStepper(stringResource(R.string.slider_dwell), config.dwellLayers, { onConfig(config.copy(dwellLayers = it)) }, dwell.first, dwell.last, 1, stringResource(R.string.slider_layers_unit), Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            Text(
                stringResource(if (config.passes > 0) R.string.slider_passes_hint else R.string.slider_one_way_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (config.espHost.isNotBlank()) SliderMoveButtons(config.espHost, plan)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.slider_pan))
            val modes = listOf(PanMode.FOLLOW to R.string.pan_follow, PanMode.FIXED to R.string.pan_fixed, PanMode.SWEEP to R.string.pan_sweep)
            SegmentedTabs(
                modes.map { Segment(stringResource(it.second)) },
                modes.indexOfFirst { it.first == config.panMode },
                { onConfig(config.copy(panMode = modes[it].first)) },
                Modifier.fillMaxWidth(),
                check = false,
                compact = true,
            )
            Text(
                stringResource(
                    when (config.panMode) {
                        PanMode.FOLLOW -> R.string.pan_follow_hint
                        PanMode.FIXED -> R.string.pan_fixed_hint
                        PanMode.SWEEP -> R.string.pan_sweep_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (config.panMode) {
                PanMode.FOLLOW -> if (targets.size > 1) {
                    Text(stringResource(R.string.slider_follow_object), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.forEachIndexed { index, target ->
                            FilterChip(
                                selected = index == config.followObject.coerceIn(targets.indices),
                                onClick = { onConfig(config.copy(followObject = index)) },
                                label = { Text(target.name, maxLines = 1) },
                            )
                        }
                    }
                }
                PanMode.FIXED -> LabeledStepper(stringResource(R.string.slider_angle), config.fixedAngle, { onConfig(config.copy(fixedAngle = it)) }, -limit, limit, 1, "°", Modifier.fillMaxWidth(0.5f))
                PanMode.SWEEP -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledStepper(stringResource(R.string.slider_sweep_start), config.sweepStart, { onConfig(config.copy(sweepStart = it)) }, -limit, limit, 1, "°", Modifier.weight(1f))
                    LabeledStepper(stringResource(R.string.slider_sweep_end), config.sweepEnd, { onConfig(config.copy(sweepEnd = it)) }, -limit, limit, 1, "°", Modifier.weight(1f))
                }
            }
            plan.saturation?.let { worst ->
                Banner(
                    Tone.WARNING,
                    if (config.panMode == PanMode.FOLLOW) stringResource(R.string.slider_saturated_follow, oneDecimal(worst), limit)
                    else stringResource(R.string.slider_saturated, limit),
                    Modifier.fillMaxWidth(),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.slider_mount))
            Text(stringResource(R.string.slider_mount_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val mount = config.mount
                val distance = SliderGeometry.RAIL_DISTANCE_RANGE
                val lateral = SliderGeometry.LATERAL_RANGE
                LabeledStepper(stringResource(R.string.slider_rail_distance), mount.railDistance, { onConfig(config.copy(mount = mount.copy(railDistance = it))) }, distance.first, distance.last, 10, "mm", Modifier.weight(1f))
                LabeledStepper(stringResource(R.string.slider_lateral), mount.lateralOffset, { onConfig(config.copy(mount = mount.copy(lateralOffset = it))) }, lateral.first, lateral.last, 10, "mm", Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.slider_esp_host), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommitField(config.espHost, { onConfig(config.copy(espHost = it.trim())) }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Uri)
                Text(stringResource(R.string.slider_esp_host_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.slider_timeline))
            Timeline(plan, layer)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendLine(MaterialTheme.colorScheme.primary, stringResource(R.string.slider_timeline_carriage, travel))
                LegendLine(PocketTheme.colors.info, stringResource(R.string.slider_timeline_pan, limit))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.slider_each_layer))
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Vai para o G-code (WAIT= da macro): vale no próximo fatiamento.
                StepRow(1, stringResource(R.string.slider_step_photo)) {
                    val range = SliderGeometry.PARK_RANGE
                    StepperField(parkMs, onParkMs, Modifier.width(170.dp), min = range.first, max = range.last, step = SliderGeometry.PAUSE_STEP, suffix = "ms")
                }
                StepRow(2, stringResource(R.string.slider_step_pause)) {
                    val range = SliderGeometry.PAUSE_RANGE
                    StepperField(config.pauseMs, { onConfig(config.copy(pauseMs = it)) }, Modifier.width(170.dp), min = range.first, max = range.last, step = SliderGeometry.PAUSE_STEP, suffix = "ms")
                }
                StepRow(3, stringResource(R.string.slider_step_move)) {
                    val next = (layer + 1).coerceAtMost(plan.layerCount - 1)
                    Text(
                        "${signed(plan.carriage(next) - plan.carriage(layer))} mm · ${signed(plan.pan(next) - plan.pan(layer))}°",
                        style = MonoStyles.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(stringResource(R.string.slider_footer, plan.layerCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Resultado da última consulta ao ESP32: o estado, ou o erro já traduzido. */
private sealed interface EspProbe {
    data object Waiting : EspProbe
    data class Ok(val status: SliderStatus) : EspProbe
    data object Failed : EspProbe
}

/** Faixa do topo com o estado do ESP32 em [host], consultado a cada 5 s enquanto o painel está aberto. */
@Composable
private fun SliderEspStatus(host: String) {
    val probe by produceState<EspProbe>(EspProbe.Waiting, host) {
        if (host.isBlank()) return@produceState
        while (true) {
            value = try {
                EspProbe.Ok(fetchSliderStatus(host))
            } catch (e: SendException) {
                // O texto do erro fala de impressora; aqui basta dizer que o slider não respondeu.
                Log.d("SliderPanel", "status $host", e.cause)
                EspProbe.Failed
            }
            delay(5_000)
        }
    }
    val (tone, text) = when {
        host.isBlank() -> Tone.INFO to stringResource(R.string.slider_esp_no_host)
        probe is EspProbe.Waiting -> Tone.INFO to stringResource(R.string.slider_esp_checking, host)
        probe is EspProbe.Failed -> Tone.WARNING to stringResource(R.string.slider_esp_unreachable, host)
        else -> {
            val status = (probe as EspProbe.Ok).status
            val problems = buildList {
                if (!status.ble) add(stringResource(R.string.slider_esp_no_ble))
                if (status.printerHost.isBlank()) add(stringResource(R.string.slider_esp_no_printer))
                else if (!status.printerReady) add(stringResource(R.string.slider_esp_printer_down, status.printerHost))
                if (status.motorError.isNotBlank()) add(stringResource(R.string.slider_esp_motor_error, status.motorError))
            }
            val plan = if (status.planLayers > 0) stringResource(R.string.slider_esp_plan, status.planLayers) else stringResource(R.string.slider_esp_no_plan)
            if (problems.isEmpty()) Tone.SUCCESS to stringResource(R.string.slider_esp_ok, plan)
            else Tone.WARNING to stringResource(R.string.slider_esp_problems, problems.joinToString(" · "), plan)
        }
    }
    Banner(tone, text, Modifier.fillMaxWidth())
}

/**
 * Botões que levam o celular à posição e ao ângulo do início e do fim do plano, para conferir o
 * enquadramento de verdade antes de imprimir, e um para parar os motores.
 */
@Composable
private fun SliderMoveButtons(host: String, plan: SliderPlan) {
    val scope = rememberCoroutineScope()
    var result by remember(host) { mutableStateOf<Pair<Tone, String>?>(null) }
    val context = LocalContext.current
    fun run(going: String?, action: suspend () -> Unit) {
        result = null
        scope.launch {
            result = try {
                action()
                Tone.SUCCESS to (going ?: context.getString(R.string.slider_stopped))
            } catch (e: SendException) {
                Log.d("SliderPanel", "move $host", e.cause)
                Tone.WARNING to if (e.text == R.string.slider_refused) context.getString(e.text, *e.args) else context.getString(R.string.slider_esp_unreachable, host)
            }
        }
    }
    fun goTo(f: Float) {
        val mm = plan.config.start + (plan.config.end - plan.config.start) * f
        val deg = plan.panAt(f)
        run(context.getString(R.string.slider_going, mm.toInt(), String.format(Locale.US, "%+.1f", deg))) { moveSlider(host, mm, deg) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton(stringResource(R.string.slider_go_start), { goTo(0f) }, Modifier.weight(1f), style = ButtonStyle.OUTLINED)
        AppButton(stringResource(R.string.slider_go_end), { goTo(1f) }, Modifier.weight(1f), style = ButtonStyle.OUTLINED)
        AppButton(stringResource(R.string.slider_stop), { run(null) { stopSlider(host) } }, style = ButtonStyle.TEXT, color = MaterialTheme.colorScheme.error)
    }
    Text(stringResource(R.string.slider_go_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    result?.let { (tone, text) -> Banner(tone, text, Modifier.fillMaxWidth()) }
}

@Composable
private fun LabeledStepper(label: String, value: Int, onCommit: (Int) -> Unit, min: Int, max: Int, step: Int, unit: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A unidade vai no rótulo: dentro do campo estreito ela esconderia o número.
        Text(if (unit.isEmpty()) label else "$label ($unit)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        StepperField(value, onCommit, Modifier.fillMaxWidth(), min = min, max = max, step = step)
    }
}

@Composable
private fun StepRow(number: Int, text: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number", style = MonoStyles.small, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun LegendLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 12.dp, height = 3.dp).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Trilho de 0 a [SliderGeometry.TRAVEL] mm: curso em laranja, carrinho na camada e a chave no zero. */
@Composable
private fun RailBar(plan: SliderPlan, layer: Int) {
    val scheme = MaterialTheme.colorScheme
    val home = PocketTheme.colors.success
    Column {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val travel = SliderGeometry.TRAVEL.toFloat()
            val bar = 6.dp.toPx()
            val y = size.height / 2
            fun x(mm: Float) = mm / travel * size.width
            drawRoundRect(scheme.surfaceContainerHighest, Offset(0f, y - bar / 2), androidx.compose.ui.geometry.Size(size.width, bar), androidx.compose.ui.geometry.CornerRadius(bar / 2))
            val start = minOf(plan.config.start, plan.config.end).toFloat()
            val end = maxOf(plan.config.start, plan.config.end).toFloat()
            drawRoundRect(scheme.primary, Offset(x(start), y - bar / 2), androidx.compose.ui.geometry.Size(x(end) - x(start), bar), androidx.compose.ui.geometry.CornerRadius(bar / 2))
            drawRect(home, Offset(0f, y - 10.dp.toPx()), androidx.compose.ui.geometry.Size(3.dp.toPx(), 20.dp.toPx()))
            val knob = 7.dp.toPx()
            val cx = x(plan.carriage(layer)).coerceIn(knob, size.width - knob)
            drawCircle(scheme.background, knob + 2.dp.toPx(), Offset(cx, y))
            drawCircle(scheme.onSurface, knob, Offset(cx, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.slider_home), style = MonoStyles.small, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${SliderGeometry.TRAVEL} mm", style = MonoStyles.small, color = scheme.onSurfaceVariant)
        }
    }
}

/** Posição do carrinho (0 a [SliderGeometry.TRAVEL]) e ângulo do pan (±limite) por camada, com a camada atual marcada. */
@Composable
private fun Timeline(plan: SliderPlan, layer: Int) {
    val scheme = MaterialTheme.colorScheme
    val panColor = PocketTheme.colors.info
    Canvas(
        Modifier.fillMaxWidth().height(104.dp).background(scheme.surfaceContainer, MaterialTheme.shapes.small),
    ) {
        val pad = 8.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val last = (plan.layerCount - 1).coerceAtLeast(1)
        val samples = 80
        drawLine(scheme.outlineVariant, Offset(pad, pad + h / 2), Offset(pad + w, pad + h / 2))
        val carriage = Path()
        val pan = Path()
        for (i in 0..samples) {
            val l = last * i / samples
            val px = pad + w * i / samples
            val cy = pad + h * (1 - plan.carriage(l) / SliderGeometry.TRAVEL)
            val py = pad + h / 2 * (1 - plan.pan(l) / SliderGeometry.PAN_LIMIT)
            if (i == 0) { carriage.moveTo(px, cy); pan.moveTo(px, py) } else { carriage.lineTo(px, cy); pan.lineTo(px, py) }
        }
        drawPath(carriage, scheme.primary, style = Stroke(2.dp.toPx()))
        drawPath(pan, panColor, style = Stroke(2.dp.toPx()))
        val cursor = pad + w * layer.coerceIn(0, last) / last
        drawLine(scheme.onSurface, Offset(cursor, pad / 2), Offset(cursor, size.height - pad / 2), 1.5.dp.toPx())
    }
}

/**
 * Barra de baixo do viewport com o slider: camada, posição do carrinho e ângulo do pan
 * (amarelo quando para no limite), e ▶ que percorre as camadas até a última.
 */
@Composable
fun SliderReadout(plan: SliderPlan, layer: Int, settings: PreviewSettings, onSettings: (PreviewSettings) -> Unit) {
    val limited = abs(plan.rawPan(layer)) > SliderGeometry.PAN_LIMIT + 0.05f
    Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        ReadoutValue(stringResource(R.string.slider_layer), "${layer + 1} / ${plan.layerCount}")
        ReadoutValue(stringResource(R.string.slider_carriage), "${oneDecimal(plan.carriage(layer))} mm")
        ReadoutValue(stringResource(R.string.slider_pan), "${signed(plan.pan(layer))}°", if (limited) PocketTheme.colors.warning else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        PlayLayersButton(plan.layerCount, settings, onSettings)
    }
}

@Composable
private fun ReadoutValue(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MonoStyles.value, color = color, maxLines = 1)
    }
}

/** ▶ leva a camada de cima até a última em [PLAY_LAYERS_MILLIS] (da última, recomeça da primeira); tocar de novo pausa. */
@Composable
private fun PlayLayersButton(layerCount: Int, settings: PreviewSettings, onSettings: (PreviewSettings) -> Unit) {
    var playing by remember(layerCount) { mutableStateOf(false) }
    val current by rememberUpdatedState(settings)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val last = layerCount - 1
        val from = current.maxLayer.coerceIn(0, last).takeIf { it < last } ?: current.minLayer.coerceIn(0, last)
        val start = withFrameMillis { it }
        while (true) {
            val elapsed = withFrameMillis { it } - start
            val layer = (from + (last - from) * elapsed / PLAY_LAYERS_MILLIS).toInt().coerceAtMost(last)
            if (layer != current.maxLayer) onSettings(current.copy(maxLayer = layer, maxMove = Int.MAX_VALUE))
            if (layer >= last) break
        }
        playing = false
    }
    AppIconButton(
        if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
        stringResource(if (playing) R.string.pause_layers else R.string.play_layers),
        { playing = !playing },
        tonal = true,
    )
}

/** Duração de ▶ da primeira à última camada. */
private const val PLAY_LAYERS_MILLIS = 12_000L

/** Rótulo e moldura do quadro em que o viewport desenha a visão do celular; [modifier] dá o tamanho e a posição dele. */
@Composable
fun PhoneViewFrame(modifier: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.slider_phone_view).uppercase(), style = MonoStyles.section, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant))
    }
}

private fun oneDecimal(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

/** "+1,4", "−0,2" ou "0,0". */
private fun signed(value: Float): String {
    val text = oneDecimal(abs(value))
    return when {
        value > 0.05f -> "+$text"
        value < -0.05f -> "−$text"
        else -> text
    }
}
