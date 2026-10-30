package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.Brush
import io.github.andredevel.slicer.core.PaintKind
import io.github.andredevel.slicer.core.PaintState
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.render.Tool
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Controles da pintura de suporte ou de costura, como a janela do gizmo do Orca: forçar,
 * bloquear ou apagar; círculo, esfera ou preenchimento; tamanho; saliências destacadas (suporte)
 * e "Apagar tudo". Os botões do mouse do Orca viram a escolha de [PaintState].
 */
@Composable
fun PaintPanel(state: SlicerUiState, viewModel: SlicerViewModel, painting: Painting) {
    val brush = state.brush
    val support = painting.kind == PaintKind.SUPPORT
    SectionLabel(stringResource(if (support) R.string.tool_paint_support else R.string.tool_paint_seam)) {
        AppIconButton(R.drawable.ic_close, stringResource(R.string.paint_done), { viewModel.selectTool(Tool.MOVE) })
    }
    Text(stringResource(R.string.paint_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (support && !supportEnabled(state, painting.objectIndex)) {
        Banner(Tone.INFO, stringResource(R.string.paint_support_off))
    }
    val states = listOf(PaintState.ENFORCE to R.string.paint_enforce, PaintState.BLOCK to R.string.paint_block, PaintState.NONE to R.string.paint_erase)
    SegmentedTabs(
        states.map { Segment(stringResource(it.second)) },
        states.indexOfFirst { it.first == brush.state },
        { viewModel.setBrush(brush.copy(state = states[it].first)) },
        Modifier.fillMaxWidth(),
        check = false,
        compact = true,
    )
    // A costura não tem o preenchimento no Orca.
    val tools = listOf(Brush.Tool.CIRCLE to R.string.paint_circle, Brush.Tool.SPHERE to R.string.paint_sphere) +
        if (support) listOf(Brush.Tool.SMART_FILL to R.string.paint_fill) else emptyList()
    val tool = brush.tool.takeIf { t -> tools.any { it.first == t } } ?: Brush.Tool.CIRCLE
    SegmentedTabs(
        tools.map { Segment(stringResource(it.second)) },
        tools.indexOfFirst { it.first == tool },
        { viewModel.setBrush(brush.copy(tool = tools[it].first)) },
        Modifier.fillMaxWidth(),
        check = false,
        compact = true,
    )
    if (tool == Brush.Tool.SMART_FILL) {
        ValueSlider(stringResource(R.string.paint_fill_angle), "${brush.smartFillAngle.roundToInt()}°", brush.smartFillAngle.toFloat(), 0f..90f) {
            viewModel.setBrush(brush.copy(smartFillAngle = it.roundToInt().toDouble()))
        }
    } else {
        // GLGizmoPainterBase::CursorRadiusMin/Max.
        ValueSlider(stringResource(R.string.paint_size), String.format(Locale.ROOT, "%.1f mm", brush.radius), brush.radius.toFloat(), 0.4f..8f) {
            viewModel.setBrush(brush.copy(radius = (it * 10).roundToInt() / 10.0))
        }
    }
    if (support) {
        ValueSlider(stringResource(R.string.paint_highlight), "${painting.highlightAngle.roundToInt()}°", painting.highlightAngle, 0f..90f) {
            viewModel.setHighlightAngle(it.roundToInt().toFloat())
        }
        LabeledRow(stringResource(R.string.paint_overhangs_only)) {
            Switch(
                checked = state.paintOnOverhangsOnly,
                onCheckedChange = viewModel::setPaintOnOverhangsOnly,
                enabled = painting.highlightAngle > 0f,
            )
        }
    }
    AppButton(
        stringResource(R.string.paint_erase_all), viewModel::clearPaint,
        style = ButtonStyle.OUTLINED, icon = R.drawable.ic_delete,
        enabled = state.editable && painting.mesh.states.isNotEmpty(),
    )
}

@Composable
private fun ValueSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        LabeledRow(label) { Text(value, style = MonoStyles.small, modifier = Modifier.widthIn(min = 56.dp)) }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

/** Suporte ligado no objeto (configuração própria) ou no processo: sem ele, forçar não tem efeito. */
private fun supportEnabled(state: SlicerUiState, objectIndex: Int): Boolean {
    val value = state.objectSettings.getOrNull(objectIndex)?.values?.get("enable_support")
        ?: state.presets[PresetKind.PROCESS]?.values?.values?.get("enable_support")
    return value != "0"
}
