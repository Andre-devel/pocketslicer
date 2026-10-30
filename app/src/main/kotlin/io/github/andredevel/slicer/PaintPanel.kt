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
import io.github.andredevel.slicer.core.SettingsTarget
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
 * Controles da pintura de suporte, de costura ou de pele difusa, como a janela do gizmo do Orca:
 * forçar, bloquear ou apagar (adicionar ou remover, na pele difusa); círculo, esfera,
 * preenchimento (suporte e pele difusa) ou lacunas (suporte); tamanho; saliências destacadas
 * (suporte); vista em corte e "Apagar tudo". Os botões do mouse do Orca viram a escolha de [PaintState].
 */
@Composable
fun PaintPanel(state: SlicerUiState, viewModel: SlicerViewModel, painting: Painting) {
    val brush = state.brush
    val support = painting.kind == PaintKind.SUPPORT
    val fuzzy = painting.kind == PaintKind.FUZZY_SKIN
    val title = when (painting.kind) {
        PaintKind.SUPPORT -> R.string.tool_paint_support
        PaintKind.SEAM -> R.string.tool_paint_seam
        PaintKind.FUZZY_SKIN -> R.string.tool_paint_fuzzy_skin
    }
    SectionLabel(stringResource(title)) {
        AppIconButton(R.drawable.ic_close, stringResource(R.string.paint_done), { viewModel.selectTool(Tool.MOVE) })
    }
    Text(stringResource(R.string.paint_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (support && !supportEnabled(state, painting.objectIndex)) {
        Banner(Tone.INFO, stringResource(R.string.paint_support_off))
    }
    // Como o aviso do GLGizmoFuzzySkin: "Só pintado" liga a pele difusa só nas áreas pintadas.
    if (fuzzy && fuzzySkinDisabled(state, painting.objectIndex)) {
        Banner(
            Tone.WARNING, stringResource(R.string.paint_fuzzy_skin_off),
            actionLabel = stringResource(R.string.paint_fuzzy_skin_enable),
            onAction = { viewModel.setSetting(SettingsTarget(painting.objectIndex), "fuzzy_skin", "none") },
        )
    }
    val states = if (fuzzy) listOf(PaintState.ENFORCE to R.string.paint_add, PaintState.NONE to R.string.paint_remove)
        else listOf(PaintState.ENFORCE to R.string.paint_enforce, PaintState.BLOCK to R.string.paint_block, PaintState.NONE to R.string.paint_erase)
    SegmentedTabs(
        states.map { Segment(stringResource(it.second)) },
        states.indexOfFirst { it.first == brush.state }.coerceAtLeast(0),
        { viewModel.setBrush(brush.copy(state = states[it].first)) },
        Modifier.fillMaxWidth(),
        check = false,
        compact = true,
    )
    // A costura não tem o preenchimento no Orca, e só o suporte tem as lacunas; null aqui é "Preencher lacunas".
    val tools = listOf(Brush.Tool.CIRCLE to R.string.paint_circle, Brush.Tool.SPHERE to R.string.paint_sphere) +
        when (painting.kind) {
            PaintKind.SUPPORT -> listOf(Brush.Tool.SMART_FILL to R.string.paint_fill, null to R.string.paint_gap_fill)
            PaintKind.FUZZY_SKIN -> listOf(Brush.Tool.SMART_FILL to R.string.paint_fill)
            PaintKind.SEAM -> emptyList()
        }
    val gapArea = state.gapArea?.takeIf { support }
    val tool = brush.tool.takeIf { t -> tools.any { it.first == t } } ?: Brush.Tool.CIRCLE
    SegmentedTabs(
        tools.map { Segment(stringResource(it.second)) },
        if (gapArea != null) tools.size - 1 else tools.indexOfFirst { it.first == tool },
        { i ->
            val chosen = tools[i].first
            if (chosen == null) viewModel.setGapArea(lastGapArea)
            else {
                if (gapArea != null) viewModel.setGapArea(null)
                viewModel.setBrush(brush.copy(tool = chosen))
            }
        },
        Modifier.fillMaxWidth(),
        check = false,
        compact = true,
    )
    if (gapArea != null) {
        Text(stringResource(R.string.paint_gap_fill_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // TriangleSelectorPatch::GapAreaMin/Max.
        ValueSlider(stringResource(R.string.paint_gap_area), String.format(Locale.ROOT, "%.2f mm²", gapArea), gapArea, 0f..5f) {
            lastGapArea = (it * 100).roundToInt() / 100f
            viewModel.setGapArea(lastGapArea)
        }
        AppButton(
            stringResource(R.string.paint_perform), viewModel::fillGaps,
            style = ButtonStyle.TONAL, enabled = state.editable && painting.gaps?.states?.isNotEmpty() == true,
        )
    } else if (tool == Brush.Tool.SMART_FILL) {
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
        if (gapArea == null) {
            LabeledRow(stringResource(R.string.paint_overhangs_only)) {
                Switch(
                    checked = state.paintOnOverhangsOnly,
                    onCheckedChange = viewModel::setPaintOnOverhangsOnly,
                    enabled = painting.highlightAngle > 0f,
                )
            }
        }
        ValueSlider(stringResource(R.string.paint_highlight), "${painting.highlightAngle.roundToInt()}°", painting.highlightAngle, 0f..90f) {
            viewModel.setHighlightAngle(it.roundToInt().toFloat())
        }
    }
    // "Vista em corte": esconde o lado do objeto voltado para a câmera, para pintar por dentro.
    ValueSlider(stringResource(R.string.paint_section_view), String.format(Locale.ROOT, "%.2f", painting.clipRatio), painting.clipRatio, 0f..1f) {
        viewModel.setClipRatio((it * 100).roundToInt() / 100f)
    }
    if (painting.clipRatio > 0f) {
        AppButton(stringResource(R.string.paint_reset_direction), viewModel::resetClipDirection, style = ButtonStyle.TEXT, icon = R.drawable.ic_refresh)
    }
    AppButton(
        stringResource(R.string.paint_erase_all), viewModel::clearPaint,
        style = ButtonStyle.OUTLINED, icon = R.drawable.ic_delete,
        enabled = state.editable && painting.mesh.states.isNotEmpty(),
    )
}

/** Área do "Preencher lacunas" da última vez, para voltar a ela ao escolhê-lo de novo (como o valor estático do Orca). */
private var lastGapArea = 0f

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

/** Pele difusa desligada no objeto ou no processo (GLGizmoFuzzySkin::on_render_input_window()). */
private fun fuzzySkinDisabled(state: SlicerUiState, objectIndex: Int): Boolean {
    val value = state.objectSettings.getOrNull(objectIndex)?.values?.get("fuzzy_skin")
        ?: state.presets[PresetKind.PROCESS]?.values?.values?.get("fuzzy_skin")
    return value == "disabled_fuzzy"
}
