package io.github.andredevel.slicer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.core.Toolpaths
import io.github.andredevel.slicer.render.ColorMode
import io.github.andredevel.slicer.render.Preview
import io.github.andredevel.slicer.render.PreviewSettings
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.FloatingCard
import io.github.andredevel.slicer.ui.components.FloatingPill
import io.github.andredevel.slicer.ui.components.MetricTile
import io.github.andredevel.slicer.ui.components.ScreenHeader
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.vertical
import io.github.andredevel.slicer.ui.theme.MonoStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val COLOR_MODES = listOf(
    ColorMode.FEATURE to R.string.color_feature,
    ColorMode.SPEED to R.string.color_speed_short,
    ColorMode.HEIGHT to R.string.color_height,
    ColorMode.WIDTH to R.string.color_width,
)

/** Barra superior de Visualizar: arquivo, placa e perfil do fatiamento; voltar a Preparar. */
@Composable
fun PreviewHeader(state: SlicerUiState, viewModel: SlicerViewModel, pickers: FilePickers, compact: Boolean) {
    val printer = state.presets[PresetKind.PRINTER]?.selected
    val plate = stringResource(R.string.plate_n, (state.plates?.selected ?: 0) + 1)
    ScreenHeader(
        title = state.modelName ?: stringResource(R.string.untitled),
        subtitle = listOfNotNull(plate, (state.slice as? SliceState.Done)?.slicedAt?.let { slicedAgo(it) }, printer).joinToString(" · "),
        modified = state.unsaved,
        busy = state.busy?.let { stringResource(it) },
    ) {
        if (compact) {
            AppIconButton(R.drawable.ic_download, stringResource(R.string.save_gcode), pickers.saveGcode, enabled = state.busy == null)
        }
        if (state.scene?.placements?.isNotEmpty() == true && compact) {
            AppIconButton(R.drawable.ic_deployed_code, stringResource(R.string.back_to_prepare), { viewModel.showPreview(false) })
        } else if (state.scene?.placements?.isNotEmpty() == true) {
            AppButton(
                stringResource(R.string.back_to_prepare), { viewModel.showPreview(false) },
                style = ButtonStyle.OUTLINED, icon = R.drawable.ic_deployed_code,
            )
        }
    }
}

/** "Fatiado há N min", atualizado a cada meio minuto. */
@Composable
private fun slicedAgo(slicedAt: Long): String {
    val now by produceState(System.currentTimeMillis(), slicedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val minutes = ((now - slicedAt) / 60_000).toInt().coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.sliced_now)
        minutes < 60 -> stringResource(R.string.sliced_minutes_ago, minutes)
        else -> stringResource(R.string.sliced_hours_ago, minutes / 60)
    }
}

/**
 * Controles da pré-visualização sobre o viewport (RF-20…RF-33): legenda com modo de cor e
 * tipos de extrusão (tocar oculta/mostra), camadas na vertical e movimentos embaixo.
 */
@Composable
fun PreviewOverlay(
    preview: Preview,
    roleLabels: List<String>,
    settings: PreviewSettings,
    onSettings: (PreviewSettings) -> Unit,
    compact: Boolean,
    viewPicker: @Composable (Modifier) -> Unit,
) {
    val toolpaths = preview.toolpaths
    val lastLayer = (toolpaths.layerCount - 1).coerceAtLeast(0)
    val maxLayer = settings.maxLayer.coerceIn(0, lastLayer)
    val minLayer = settings.minLayer.coerceIn(0, maxLayer)
    val moves = preview.movesInLayer(maxLayer)
    var legendOpen by rememberSaveable { mutableStateOf(!compact) }

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.align(Alignment.TopStart).widthIn(max = LEGEND_WIDTH).padding(bottom = if (moves > 0) MOVES_BAR_CLEARANCE else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingPill(
                        stringResource(COLOR_MODES.first { it.first == settings.colorMode }.second),
                        { legendOpen = !legendOpen },
                        icon = R.drawable.ic_arrow_drop_down,
                        emphasized = legendOpen,
                    )
                    viewPicker(Modifier)
                }
            } else {
                viewPicker(Modifier)
            }
            if (legendOpen) Legend(preview, roleLabels, settings, onSettings, Modifier.weight(1f, fill = false))
        }

        if (lastLayer > 0) {
            LayerSlider(
                toolpaths, minLayer, maxLayer, lastLayer, settings, onSettings,
                Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(
                    bottom = (if (moves > 0) MOVES_BAR_CLEARANCE else 0.dp) + (if (compact) SHEET_CLEARANCE else 0.dp),
                ),
            )
        }

        if (moves > 0) {
            FloatingCard(Modifier.align(Alignment.BottomCenter).padding(bottom = if (compact) SHEET_CLEARANCE else 0.dp).widthIn(max = 460.dp).fillMaxWidth(), RoundedCornerShape(28.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.moves), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = settings.maxMove.coerceAtMost(moves).toFloat(),
                        onValueChange = { onSettings(settings.copy(maxMove = it.roundToInt())) },
                        valueRange = 0f..moves.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text("${settings.maxMove.coerceAtMost(moves)} / $moves", style = MonoStyles.small)
                }
            }
        }
    }
}

@Composable
private fun Legend(preview: Preview, roleLabels: List<String>, settings: PreviewSettings, onSettings: (PreviewSettings) -> Unit, modifier: Modifier) {
    FloatingCard(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.color_by), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedTabs(
                COLOR_MODES.map { Segment(stringResource(it.second)) },
                COLOR_MODES.indexOfFirst { it.first == settings.colorMode },
                { onSettings(settings.copy(colorMode = COLOR_MODES[it].first)) },
                check = false,
                compact = true,
            )
            if (settings.colorMode == ColorMode.FEATURE) RoleLegend(preview, roleLabels, settings, onSettings)
            else RangeLegend(preview, settings.colorMode)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.travels), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = settings.showTravels, onCheckedChange = { onSettings(settings.copy(showTravels = it)) })
            }
        }
    }
}

/** Tipos de extrusão presentes, com a fração do volume extrudado; tocar oculta/mostra. */
@Composable
private fun RoleLegend(preview: Preview, roleLabels: List<String>, settings: PreviewSettings, onSettings: (PreviewSettings) -> Unit) {
    val shares by produceState<Map<Int, Float>>(emptyMap(), preview) {
        value = withContext(Dispatchers.Default) { roleShares(preview.toolpaths) }
    }
    val present = remember(preview) { preview.toolpaths.roles.map { it.toInt() and 0xFF }.toSortedSet() }
    for (role in present) {
        val hidden = role in settings.hiddenRoles
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clickable { onSettings(settings.copy(hiddenRoles = if (hidden) settings.hiddenRoles - role else settings.hiddenRoles + role)) }
                .alpha(if (hidden) 0.4f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).background(rgb(Preview.roleColor(role)), RoundedCornerShape(3.dp)))
            Text(
                roleLabels.getOrElse(role) { "?" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            shares[role]?.let { Text(String.format(Locale.getDefault(), "%.1f%%", it * 100), style = MonoStyles.small) }
        }
    }
}

/** Fração do volume extrudado (comprimento × largura × altura) de cada tipo. */
private fun roleShares(toolpaths: Toolpaths): Map<Int, Float> {
    val volume = DoubleArray(256)
    val e = toolpaths.extrusions
    for (i in 0 until toolpaths.segmentCount) {
        val o = i * 9
        val dx = e[o + 3] - e[o]
        val dy = e[o + 4] - e[o + 1]
        val dz = e[o + 5] - e[o + 2]
        volume[toolpaths.roles[i].toInt() and 0xFF] += sqrt(dx * dx + dy * dy + dz * dz).toDouble() * e[o + 6] * e[o + 7]
    }
    val total = volume.sum().takeIf { it > 0 } ?: return emptyMap()
    return volume.withIndex().filter { it.value > 0 }.associate { it.index to (it.value / total).toFloat() }
}

@Composable
private fun RangeLegend(preview: Preview, mode: ColorMode) {
    val range = remember(preview, mode) { preview.range(mode) } ?: return
    val unit = if (mode == ColorMode.SPEED) "mm/s" else "mm"
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(12.dp)
            .background(Brush.horizontalGradient(Preview.RANGE_COLORS.map(::rgb)), RoundedCornerShape(3.dp)),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(String.format(Locale.getDefault(), "%.2f %s", range.start, unit), style = MonoStyles.small)
        Text(String.format(Locale.getDefault(), "%.2f %s", range.endInclusive, unit), style = MonoStyles.small)
    }
}

/** Faixa de camadas visíveis na vertical, com a camada e o z nas pontas. */
@Composable
private fun LayerSlider(
    toolpaths: Toolpaths,
    minLayer: Int,
    maxLayer: Int,
    lastLayer: Int,
    settings: PreviewSettings,
    onSettings: (PreviewSettings) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LayerLabel(maxLayer + 1, toolpaths.layerZ.getOrElse(maxLayer) { 0f }, highlight = true)
        RangeSlider(
            value = minLayer.toFloat()..maxLayer.toFloat(),
            onValueChange = { range ->
                // Ao trocar a camada de cima, ela aparece inteira.
                onSettings(settings.copy(minLayer = range.start.roundToInt(), maxLayer = range.endInclusive.roundToInt(), maxMove = Int.MAX_VALUE))
            },
            valueRange = 0f..lastLayer.toFloat(),
            modifier = Modifier.weight(1f).vertical(),
        )
        LayerLabel(minLayer + 1, toolpaths.layerZ.getOrElse(minLayer) { 0f }, highlight = false)
    }
}

@Composable
private fun LayerLabel(layer: Int, z: Float, highlight: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .background(if (highlight) scheme.inverseSurface else scheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        val color = if (highlight) scheme.inverseOnSurface else scheme.onSurface
        Text("$layer", style = MonoStyles.value, color = color, maxLines = 1)
        Text(String.format(Locale.getDefault(), "%.2f mm", z), style = MonoStyles.small, color = color.copy(alpha = 0.7f), maxLines = 1, softWrap = false)
    }
}

/** Painel lateral de Visualizar: resumo do fatiamento e as saídas do G-code. */
@Composable
fun PreviewPanel(state: SlicerUiState, pickers: FilePickers, onSend: () -> Unit) {
    val stats = (state.slice as? SliceState.Done)?.stats ?: return
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.summary_plate, (state.plates?.selected ?: 0) + 1))
        Column {
            Text(stringResource(R.string.estimated_time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(stats.printTimeSeconds), style = MonoStyles.display)
        }
        SummaryTiles(stats)
        Spacer(Modifier.weight(1f))
        GcodeActions(state, pickers, onSend)
    }
}

/** Sheet de Visualizar no celular. */
@Composable
fun PreviewSheet(state: SlicerUiState, pickers: FilePickers, onSend: () -> Unit) {
    val stats = (state.slice as? SliceState.Done)?.stats ?: return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
            Text(formatDuration(stats.printTimeSeconds), style = MonoStyles.title, modifier = Modifier.weight(1f))
        }
        GcodeActions(state, pickers, onSend, compact = true)
        SummaryTiles(stats)
    }
}

@Composable
private fun SummaryTiles(stats: SliceStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile(
            stringResource(R.string.filament),
            String.format(Locale.getDefault(), "%.2f m", stats.filamentMeters),
            Modifier.weight(1f),
            detail = String.format(Locale.getDefault(), "%.1f g", stats.filamentGrams),
        )
        MetricTile(stringResource(R.string.cost), String.format(Locale.getDefault(), "%.2f", stats.cost), Modifier.weight(1f))
    }
}

@Composable
private fun GcodeActions(state: SlicerUiState, pickers: FilePickers, onSend: () -> Unit, compact: Boolean = false) {
    val enabled = state.busy == null
    if (!compact) {
        AppButton(stringResource(R.string.save_gcode), pickers.saveGcode, Modifier.fillMaxWidth(), ButtonStyle.OUTLINED, R.drawable.ic_download, enabled)
    }
    AppButton(stringResource(R.string.send_to_printer), onSend, Modifier.fillMaxWidth(), icon = R.drawable.ic_send, enabled = enabled)
}

/** "2h 47min" (sempre em horas e minutos, como o Orca). */
fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).toLong()
    return String.format(Locale.ROOT, "%dh %02dmin", minutes / 60, minutes % 60)
}

private fun rgb(color: Int) = Color(0xFF000000.toInt() or color)

private val LEGEND_WIDTH = 320.dp

/** Altura da barra de movimentos mais o espaço até a legenda. */
private val MOVES_BAR_CLEARANCE = 72.dp

/** Altura do sheet recolhido no celular: a barra de movimentos fica acima dele. */
private val SHEET_CLEARANCE = 128.dp
