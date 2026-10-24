package io.github.andredevel.slicer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.render.ColorMode
import io.github.andredevel.slicer.render.Preview
import io.github.andredevel.slicer.render.PreviewSettings
import java.util.Locale
import kotlin.math.roundToInt

private val COLOR_MODES = listOf(
    ColorMode.FEATURE to R.string.color_feature,
    ColorMode.SPEED to R.string.color_speed,
    ColorMode.HEIGHT to R.string.color_height,
    ColorMode.WIDTH to R.string.color_width,
)

/**
 * Controles da pré-visualização sobre o viewport (RF-20…RF-33): legenda com modo de cor e
 * tipos de extrusão (tocar oculta/mostra), estatísticas e barras de camadas e de movimentos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewOverlay(
    preview: Preview,
    stats: SliceStats,
    roleLabels: List<String>,
    settings: PreviewSettings,
    onSettings: (PreviewSettings) -> Unit,
) {
    val toolpaths = preview.toolpaths
    val lastLayer = (toolpaths.layerCount - 1).coerceAtLeast(0)
    val maxLayer = settings.maxLayer.coerceIn(0, lastLayer)
    val minLayer = settings.minLayer.coerceIn(0, maxLayer)
    val moves = preview.movesInLayer(maxLayer)

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.width(260.dp),
            ) {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((mode, label) in COLOR_MODES) {
                            FilterChip(selected = settings.colorMode == mode, onClick = { onSettings(settings.copy(colorMode = mode)) },
                                label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    if (settings.colorMode == ColorMode.FEATURE) {
                        RoleLegend(preview, roleLabels, settings, onSettings)
                    } else {
                        RangeLegend(preview, settings.colorMode)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = settings.showTravels, onCheckedChange = { onSettings(settings.copy(showTravels = it)) })
                        Text(stringResource(R.string.travels), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ) {
                Text(statsText(stats), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val z = toolpaths.layerZ.getOrElse(maxLayer) { 0f }
                Text(stringResource(R.string.layers_label, minLayer + 1, maxLayer + 1, toolpaths.layerCount, z),
                    style = MaterialTheme.typography.bodySmall)
                if (lastLayer > 0) {
                    RangeSlider(
                        value = minLayer.toFloat()..maxLayer.toFloat(),
                        onValueChange = { range ->
                            // Ao trocar a camada de cima, ela aparece inteira.
                            onSettings(settings.copy(minLayer = range.start.roundToInt(), maxLayer = range.endInclusive.roundToInt(), maxMove = Int.MAX_VALUE))
                        },
                        valueRange = 0f..lastLayer.toFloat(),
                    )
                }
                Text(stringResource(R.string.moves_label, settings.maxMove.coerceAtMost(moves), moves), style = MaterialTheme.typography.bodySmall)
                if (moves > 0) {
                    Slider(
                        value = settings.maxMove.coerceAtMost(moves).toFloat(),
                        onValueChange = { onSettings(settings.copy(maxMove = it.roundToInt())) },
                        valueRange = 0f..moves.toFloat(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleLegend(preview: Preview, roleLabels: List<String>, settings: PreviewSettings, onSettings: (PreviewSettings) -> Unit) {
    val present = remember(preview) { preview.toolpaths.roles.map { it.toInt() and 0xFF }.toSortedSet() }
    for (role in present) {
        val hidden = role in settings.hiddenRoles
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = !hidden, onCheckedChange = {
                onSettings(settings.copy(hiddenRoles = if (hidden) settings.hiddenRoles - role else settings.hiddenRoles + role))
            })
            Box(Modifier.size(14.dp).background(rgb(Preview.roleColor(role)), RoundedCornerShape(3.dp)))
            Text(roleLabels.getOrElse(role) { "?" }, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RangeLegend(preview: Preview, mode: ColorMode) {
    val range = remember(preview, mode) { preview.range(mode) } ?: return
    val unit = if (mode == ColorMode.SPEED) "mm/s" else "mm"
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(14.dp)
        .background(Brush.horizontalGradient(Preview.RANGE_COLORS.map(::rgb)), RoundedCornerShape(3.dp)))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(String.format(Locale.getDefault(), "%.2f %s", range.start, unit), style = MaterialTheme.typography.bodySmall)
        Text(String.format(Locale.getDefault(), "%.2f %s", range.endInclusive, unit), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun statsText(stats: SliceStats): String {
    val minutes = (stats.printTimeSeconds / 60).toLong()
    return stringResource(
        R.string.preview_stats,
        String.format(Locale.ROOT, "%dh%02dmin", minutes / 60, minutes % 60),
        String.format(Locale.getDefault(), "%.2f", stats.filamentMeters),
        String.format(Locale.getDefault(), "%.1f", stats.filamentGrams),
        String.format(Locale.getDefault(), "%.2f", stats.cost),
    )
}

private fun rgb(color: Int) = Color(0xFF000000.toInt() or color)
