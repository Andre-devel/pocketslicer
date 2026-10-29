package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.Placement
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.render.Tool
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.AxisFields
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.EmptyState
import io.github.andredevel.slicer.ui.components.FloatingCard
import io.github.andredevel.slicer.ui.components.FloatingPill
import io.github.andredevel.slicer.ui.components.InfoCard
import io.github.andredevel.slicer.ui.components.NavigationRow
import io.github.andredevel.slicer.ui.components.PillTabs
import io.github.andredevel.slicer.ui.components.ScreenHeader
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.ToolButton
import io.github.andredevel.slicer.ui.components.ToolColumn
import io.github.andredevel.slicer.ui.theme.MonoStyles

private val TOOLS = listOf(
    Triple(Tool.MOVE, R.drawable.ic_open_with, R.string.tool_move),
    Triple(Tool.ROTATE, R.drawable.ic_rotate_right, R.string.tool_rotate),
    Triple(Tool.SCALE, R.drawable.ic_open_in_full, R.string.tool_scale),
    Triple(Tool.LAY_ON_FACE, R.drawable.ic_vertical_align_bottom, R.string.tool_lay_on_face),
)

private val SlicerUiState.editable get() = busy == null && slice !is SliceState.Running

/** Número da placa na mesa, a partir de 1. */
private val SlicerUiState.plateNumber get() = (plates?.selected ?: 0) + 1

/** Barra superior de Preparar: nome do projeto, menu de arquivo e, na tela larga, Fatiar. */
@Composable
fun PrepareHeader(state: SlicerUiState, viewModel: SlicerViewModel, pickers: FilePickers, compact: Boolean) {
    val subtitle = state.plates?.let { pluralStringResource(R.plurals.plate_count, it.objects.size, it.objects.size) }
        ?: if (state.objectCount == 0) stringResource(R.string.new_project)
        else pluralStringResource(R.plurals.object_count, state.objectCount, state.objectCount)
    ScreenHeader(
        title = state.modelName ?: stringResource(R.string.untitled),
        subtitle = subtitle,
        busy = state.busy?.let { stringResource(it) },
    ) {
        FileMenu(state, pickers)
        AppIconButton(
            R.drawable.ic_save, stringResource(R.string.save_project), pickers.saveProject,
            enabled = state.editable && state.objectCount > 0,
        )
        if (!compact) {
            Spacer(Modifier.padding(start = 4.dp))
            SliceButton(state, viewModel)
        }
    }
}

/** Menu "Arquivo": abrir, adicionar modelo e salvar o projeto. */
@Composable
private fun FileMenu(state: SlicerUiState, pickers: FilePickers) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(R.drawable.ic_folder_open, stringResource(R.string.file_menu), { open = true }, tonal = open)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_file)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_file_open), null) },
                trailingIcon = { Text(stringResource(R.string.open_file_types), style = MonoStyles.small, color = MaterialTheme.colorScheme.outline) },
                enabled = state.editable,
                onClick = { open = false; pickers.openFile() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_model)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add_box), null) },
                enabled = state.editable,
                onClick = { open = false; pickers.addModel() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_project)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_save), null) },
                enabled = state.editable && state.objectCount > 0,
                onClick = { open = false; pickers.saveProject() },
            )
        }
    }
}

/**
 * Ação principal de Preparar conforme o fatiamento: Fatiar, Cancelar durante ou, com o G-code
 * pronto, ir para a visualização.
 */
@Composable
fun SliceButton(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier = Modifier) {
    when (state.slice) {
        is SliceState.Running -> AppButton(
            stringResource(R.string.cancel), viewModel::cancelSlice, modifier,
            style = ButtonStyle.OUTLINED, icon = R.drawable.ic_close,
        )
        is SliceState.Done -> AppButton(
            stringResource(R.string.view_gcode), { viewModel.showPreview(true) }, modifier,
            style = ButtonStyle.TONAL, icon = R.drawable.ic_layers,
        )
        SliceState.Idle -> AppButton(
            stringResource(R.string.slice_plate, state.plateNumber), viewModel::slice, modifier,
            icon = R.drawable.ic_bolt, enabled = state.canSlice,
        )
    }
}

/** Controles sobre o viewport em Preparar: ferramentas, avisos, placas e estado vazio. */
@Composable
fun PrepareOverlay(state: SlicerUiState, viewModel: SlicerViewModel, pickers: FilePickers, compact: Boolean) {
    val editable = state.editable
    val selected = state.scene?.selected != null
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        if (!compact) {
            ToolColumn(Modifier.align(Alignment.TopStart)) {
                if (selected) {
                    ToolButtons(state, viewModel)
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                ToolButton(R.drawable.ic_add_box, stringResource(R.string.add), false, pickers.addModel, enabled = editable)
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(horizontal = if (compact) 0.dp else 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val outside = state.scene?.placements?.indexOfFirst { it.outside } ?: -1
            if (outside >= 0) {
                val count = state.scene!!.placements.count { it.outside }
                Banner(
                    Tone.ERROR,
                    stringResource(R.string.outside_detail),
                    Modifier.widthIn(max = 460.dp),
                    title = pluralStringResource(R.plurals.outside_count, count, count),
                    actionLabel = stringResource(R.string.show),
                    onAction = { viewModel.select(outside) },
                )
            }
        }
        val slice = state.slice
        when {
            slice is SliceState.Running -> SliceProgress(slice, Modifier.align(Alignment.Center))
            state.objectCount == 0 && state.busy == null -> EmptyState(
                R.drawable.ic_deployed_code,
                stringResource(R.string.empty_title),
                stringResource(R.string.empty_text),
                Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            ) {
                AppButton(stringResource(R.string.open_file_short), pickers.openFile, icon = R.drawable.ic_file_open)
            }
        }
        BottomControls(state, viewModel, compact)
    }
}

@Composable
private fun BoxScope.BottomControls(state: SlicerUiState, viewModel: SlicerViewModel, compact: Boolean) {
    val editable = state.editable
    val plates = state.plates
    // No celular o sheet recolhido cobre a faixa de baixo: placas e ações sobem para o topo.
    val bottom = if (compact) Modifier.align(Alignment.TopStart).padding(top = if (state.anyOutside) 110.dp else 0.dp)
    else Modifier.align(Alignment.BottomStart)
    Row(bottom.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plates != null) {
            PillTabs(
                plates.objects.indices.map { if (compact) "${it + 1}" else stringResource(R.string.plate_n, it + 1) },
                plates.selected,
                viewModel::selectPlate,
                enabled = { editable && plates.objects[it] > 0 },
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.objectCount > 0) {
            if (!compact) {
                FloatingCard {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_category), null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pluralStringResource(R.plurals.objects_short, state.objectCount, state.objectCount), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            FloatingPill(
                stringResource(R.string.arrange), viewModel::arrange,
                icon = R.drawable.ic_auto_awesome_mosaic, enabled = editable, emphasized = true,
            )
        }
    }
}

@Composable
private fun ToolButtons(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier = Modifier) {
    for ((tool, icon, label) in TOOLS) {
        ToolButton(icon, stringResource(label), state.tool == tool, { viewModel.selectTool(tool) }, modifier, enabled = state.editable)
    }
}

/** Cartão de progresso do fatiamento, sobre o viewport. */
@Composable
private fun SliceProgress(slice: SliceState.Running, modifier: Modifier) {
    FloatingCard(modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.slicing), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${slice.percent}%", style = MonoStyles.title, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { slice.percent / 100f }, modifier = Modifier.fillMaxWidth())
            if (slice.text.isNotEmpty()) {
                Text(slice.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Painel lateral de Preparar: perfis ativos e o objeto selecionado. */
@Composable
fun PreparePanel(state: SlicerUiState, viewModel: SlicerViewModel, openParameters: (PresetKind) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileSummary(state, openParameters)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SelectedObject(state, viewModel)
        }
        Box(Modifier.padding(16.dp)) {
            if (state.anyOutside) {
                Banner(Tone.ERROR, stringResource(R.string.slice_blocked_outside), Modifier.fillMaxWidth(), icon = R.drawable.ic_block)
            } else {
                InfoCard(stringResource(R.string.on_device_note), icon = R.drawable.ic_lock)
            }
        }
    }
}

/** Sheet de Preparar no celular: Fatiar, o objeto selecionado e os perfis. */
@Composable
fun PrepareSheet(state: SlicerUiState, viewModel: SlicerViewModel, pickers: FilePickers, openParameters: (PresetKind) -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SliceButton(state, viewModel, Modifier.weight(1f))
            AppIconButton(R.drawable.ic_add_box, stringResource(R.string.add_model), pickers.addModel, enabled = state.editable, tonal = true)
        }
        if (state.scene?.selected != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolButtons(state, viewModel, Modifier.weight(1f))
            }
        }
        SelectedObject(state, viewModel)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ProfileSummary(state, openParameters)
    }
}

/** Perfis ativos; tocar abre o tipo em Parâmetros. */
@Composable
private fun ProfileSummary(state: SlicerUiState, openParameters: (PresetKind) -> Unit) {
    SectionLabel(stringResource(R.string.profile))
    val vendor = state.vendors.selected
    val rows = listOf(
        Triple(PresetKind.PRINTER, R.drawable.ic_print, if (vendor != null) stringResource(R.string.printer_from, vendor) else stringResource(R.string.printer)),
        Triple(PresetKind.PROCESS, R.drawable.ic_stacks, stringResource(R.string.process)),
        Triple(PresetKind.FILAMENT, R.drawable.ic_line_weight, stringResource(R.string.filament)),
    )
    for ((kind, icon, overline) in rows) {
        val choice = state.presets[kind]
        NavigationRow(
            icon, overline, choice?.selected ?: "—", { openParameters(kind) },
            tag = choice?.values?.dirty?.size?.takeIf { it > 0 }?.let { stringResource(R.string.modified_count, it) },
        )
    }
}

/** Objeto selecionado: ações e campos de posição, rotação e escala. */
@Composable
private fun SelectedObject(state: SlicerUiState, viewModel: SlicerViewModel) {
    val index = state.scene?.selected
    val placement = state.selectedPlacement
    SectionLabel(stringResource(R.string.object_title)) {
        if (index != null) {
            AppIconButton(R.drawable.ic_content_copy, stringResource(R.string.duplicate), { viewModel.duplicate(index) }, enabled = state.editable)
            AppIconButton(
                R.drawable.ic_delete, stringResource(R.string.delete), { viewModel.remove(index) },
                enabled = state.editable, tint = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (index == null || placement == null) {
        Text(stringResource(R.string.object_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(
        stringResource(R.string.object_n, index + 1),
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    PlacementFields(index, placement, viewModel, state.editable)
}

/** Campos numéricos do objeto selecionado: posição, rotação e escala. */
@Composable
private fun PlacementFields(objectIndex: Int, placement: Placement, viewModel: SlicerViewModel, enabled: Boolean) {
    var uniform by rememberSaveable { mutableStateOf(true) }
    fun commit(offset: FloatArray = placement.offset, rotation: FloatArray = placement.rotation, scale: FloatArray = placement.scale) =
        viewModel.setPlacement(objectIndex, offset, rotation, scale)

    FieldLabel(stringResource(R.string.position))
    AxisFields(placement.offset, { axis, value -> commit(offset = placement.offset.copyOf().also { it[axis] = value }) }, axes = 2, suffix = "mm", enabled = enabled)
    FieldLabel(stringResource(R.string.rotation))
    AxisFields(placement.rotation, { axis, value -> commit(rotation = placement.rotation.copyOf().also { it[axis] = value }) }, suffix = "°", enabled = enabled)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(stringResource(R.string.scale), Modifier.weight(1f))
        Text(stringResource(R.string.uniform), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = uniform, onCheckedChange = { uniform = it })
    }
    AxisFields(FloatArray(3) { placement.scale[it] * 100f }, { axis, value ->
        val factor = value / 100f
        val scale = if (uniform) {
            // Mantém a proporção entre os eixos.
            val ratio = factor / placement.scale[axis]
            FloatArray(3) { placement.scale[it] * ratio }
        } else {
            placement.scale.copyOf().also { it[axis] = factor }
        }
        commit(scale = scale)
    }, suffix = "%", min = 0.01f, enabled = enabled)
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 4.dp))
}
