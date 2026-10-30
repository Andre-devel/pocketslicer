package io.github.andredevel.slicer

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/** Ferramentas de cada pintura, como nos gizmos do Orca (a costura não tem o preenchimento). */
internal val PaintKind.tools get() = when (this) {
    PaintKind.SUPPORT, PaintKind.FUZZY_SKIN -> listOf(Brush.Tool.CIRCLE, Brush.Tool.SPHERE, Brush.Tool.SMART_FILL)
    PaintKind.SEAM -> listOf(Brush.Tool.CIRCLE, Brush.Tool.SPHERE)
    PaintKind.COLOR -> listOf(Brush.Tool.CIRCLE, Brush.Tool.SPHERE, Brush.Tool.TRIANGLE, Brush.Tool.BUCKET_FILL, Brush.Tool.HEIGHT_RANGE)
}

/** Pinturas com "Preencher lacunas". */
internal val PaintKind.hasGapFill get() = this == PaintKind.SUPPORT || this == PaintKind.COLOR

/** Pinturas com o estado bloquear ([PaintState.BLOCK]). */
internal val PaintKind.hasBlock get() = this == PaintKind.SUPPORT || this == PaintKind.SEAM

/**
 * Controles da pintura de suporte, de costura, de pele difusa ou de cor, como a janela do gizmo
 * do Orca: forçar, bloquear ou apagar (adicionar ou remover, na pele difusa; pintar ou apagar, na
 * cor, com o filamento escolhido); as ferramentas de [PaintKind.tools] ou lacunas; tamanho;
 * saliências destacadas (suporte); vista em corte e "Apagar tudo". Os botões do mouse do Orca
 * viram a escolha de [PaintState].
 */
@Composable
fun PaintPanel(state: SlicerUiState, viewModel: SlicerViewModel, painting: Painting) {
    val brush = state.brush
    val support = painting.kind == PaintKind.SUPPORT
    val fuzzy = painting.kind == PaintKind.FUZZY_SKIN
    val color = painting.kind == PaintKind.COLOR
    val title = when (painting.kind) {
        PaintKind.SUPPORT -> R.string.tool_paint_support
        PaintKind.SEAM -> R.string.tool_paint_seam
        PaintKind.FUZZY_SKIN -> R.string.tool_paint_fuzzy_skin
        PaintKind.COLOR -> R.string.tool_paint_color
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
    val states = when {
        fuzzy -> listOf(PaintState.ENFORCE to R.string.paint_add, PaintState.NONE to R.string.paint_remove)
        color -> listOf(PaintState.ENFORCE to R.string.paint_paint, PaintState.NONE to R.string.paint_erase)
        else -> listOf(PaintState.ENFORCE to R.string.paint_enforce, PaintState.BLOCK to R.string.paint_block, PaintState.NONE to R.string.paint_erase)
    }
    SegmentedTabs(
        states.map { Segment(stringResource(it.second)) },
        states.indexOfFirst { it.first == brush.state }.coerceAtLeast(0),
        { viewModel.setBrush(brush.copy(state = states[it].first)) },
        Modifier.fillMaxWidth(),
        check = false,
        compact = true,
    )
    if (color) {
        val colors = state.scene?.filamentColors.orEmpty()
        LabeledRow(stringResource(R.string.paint_filament)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                colors.forEachIndexed { i, rgba ->
                    val chosen = i + 1 == brush.color.coerceIn(1, colors.size)
                    ColorSwatch(
                        rgba, "${i + 1}",
                        Modifier
                            .border(3.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.setBrush(brush.copy(color = i + 1, state = PaintState.ENFORCE)) }
                            .semantics { selected = chosen },
                    )
                }
            }
        }
        if (colors.size < 2) Banner(Tone.INFO, stringResource(R.string.paint_color_one_filament))
    }
    // null aqui é "Preencher lacunas". Com muitas, em duas linhas.
    val tools = painting.kind.tools.map { it to toolLabel(it) } + listOfNotNull((null to R.string.paint_gap_fill).takeIf { painting.kind.hasGapFill })
    val gapArea = state.gapArea?.takeIf { painting.kind.hasGapFill }
    val tool = brush.tool.takeIf { it in painting.kind.tools } ?: Brush.Tool.CIRCLE
    val current = if (gapArea != null) tools.size - 1 else tools.indexOfFirst { it.first == tool }
    val rowSize = if (tools.size > 4) (tools.size + 1) / 2 else tools.size
    tools.chunked(rowSize).forEachIndexed { row, segment ->
        SegmentedTabs(
            segment.map { Segment(stringResource(it.second)) },
            current - row * rowSize,
            { i ->
                val chosen = segment[i].first
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
    }
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
    } else if (tool == Brush.Tool.SMART_FILL || tool == Brush.Tool.BUCKET_FILL) {
        // O balde da pintura de cor só limita o ângulo com "Detecção de bordas".
        val bucket = tool == Brush.Tool.BUCKET_FILL
        if (bucket) {
            LabeledRow(stringResource(R.string.paint_edge_detection)) {
                Switch(checked = state.edgeDetection, onCheckedChange = viewModel::setEdgeDetection)
            }
        }
        if (!bucket || state.edgeDetection) {
            ValueSlider(stringResource(R.string.paint_fill_angle), "${brush.smartFillAngle.roundToInt()}°", brush.smartFillAngle.toFloat(), 0f..90f) {
                viewModel.setBrush(brush.copy(smartFillAngle = it.roundToInt().toDouble()))
            }
        }
    } else if (tool == Brush.Tool.TRIANGLE) {
        Text(stringResource(R.string.paint_triangle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (tool == Brush.Tool.HEIGHT_RANGE) {
        // GLGizmoPainterBase::CursorHeightMin/Max.
        ValueSlider(stringResource(R.string.paint_height_range), String.format(Locale.ROOT, "%.1f mm", brush.height), brush.height.toFloat(), 0.1f..8f) {
            viewModel.setBrush(brush.copy(height = ((it * 10).roundToInt() / 10.0).coerceAtLeast(0.1)))
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
        // Na cor, a malha tem todos os triângulos, pintados ou não.
        enabled = state.editable && (color || painting.mesh.states.isNotEmpty()),
    )
}

@StringRes
private fun toolLabel(tool: Brush.Tool) = when (tool) {
    Brush.Tool.CIRCLE -> R.string.paint_circle
    Brush.Tool.SPHERE -> R.string.paint_sphere
    Brush.Tool.SMART_FILL, Brush.Tool.BUCKET_FILL -> R.string.paint_fill
    Brush.Tool.TRIANGLE -> R.string.paint_triangle
    Brush.Tool.HEIGHT_RANGE -> R.string.paint_height
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
