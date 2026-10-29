package io.github.andredevel.slicer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.andredevel.slicer.core.Placement
import io.github.andredevel.slicer.core.Plates
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
import io.github.andredevel.slicer.ui.components.StepRow
import io.github.andredevel.slicer.ui.components.StepStatus
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SelectField
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

/** Barra superior de Preparar: nome do projeto, menu de arquivo e, na tela larga, Fatiar. */
@Composable
fun PrepareHeader(state: SlicerUiState, viewModel: SlicerViewModel, pickers: FilePickers, compact: Boolean) {
    val plateCount = state.plates.objects.size
    val subtitle = if (plateCount > 1) pluralStringResource(R.plurals.plate_count, plateCount, plateCount)
        else if (state.objectCount == 0) stringResource(R.string.new_project)
        else pluralStringResource(R.plurals.object_count, state.objectCount, state.objectCount)
    ScreenHeader(
        title = state.modelName ?: stringResource(R.string.untitled),
        subtitle = if (state.unsaved) "$subtitle · ${stringResource(R.string.unsaved_changes)}" else subtitle,
        modified = state.unsaved,
        busy = state.busy?.let { stringResource(it) },
    ) {
        FileMenu(state, pickers, viewModel::openRecent)
        AppIconButton(
            R.drawable.ic_save, stringResource(R.string.save_project), pickers.saveProject,
            enabled = state.editable && state.projectObjectCount > 0,
        )
        if (!compact) {
            Spacer(Modifier.padding(start = 4.dp))
            SliceButton(state, viewModel)
        }
    }
}

/** Menu "Arquivo": abrir, adicionar modelo e salvar o projeto. */
@Composable
private fun FileMenu(state: SlicerUiState, pickers: FilePickers, openRecent: (RecentFile) -> Unit) {
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
            if (state.recents.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.recent_files),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                for (recent in state.recents) {
                    DropdownMenuItem(
                        text = { Text(recent.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_history), null) },
                        enabled = state.editable,
                        onClick = { open = false; openRecent(recent) },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_project)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_save), null) },
                enabled = state.editable && state.projectObjectCount > 0,
                onClick = { open = false; pickers.saveProject() },
            )
        }
    }
}

/**
 * Pede a permissão de notificações (Android 13+) para o andamento do fatiamento em segundo plano;
 * sem ela o fatiamento continua, só sem notificação.
 */
@Composable
private fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Ação principal de Preparar conforme o fatiamento: Fatiar, Cancelar durante ou, com o G-code
 * pronto e em dia, ir para a visualização.
 */
@Composable
fun SliceButton(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier = Modifier) {
    val requestNotifications = rememberNotificationPermission()
    val slice = state.slice
    when {
        slice is SliceState.Running -> AppButton(
            stringResource(R.string.cancel), viewModel::cancelSlice, modifier,
            style = ButtonStyle.OUTLINED, icon = R.drawable.ic_close,
        )
        slice is SliceState.Done && slice.stale == null -> AppButton(
            stringResource(R.string.view_gcode), { viewModel.showPreview(true) }, modifier,
            style = ButtonStyle.TONAL, icon = R.drawable.ic_layers,
        )
        // Sem G-code ou com ele desatualizado.
        else -> AppButton(
            stringResource(R.string.slice_plate, state.plateNumber), { requestNotifications(); viewModel.slice() }, modifier,
            icon = R.drawable.ic_bolt, enabled = state.canSlice,
        )
    }
}

/** Controles sobre o viewport em Preparar: ferramentas, avisos, placas e estado vazio. */
@Composable
fun PrepareOverlay(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    pickers: FilePickers,
    compact: Boolean,
    viewPicker: @Composable (Modifier) -> Unit,
) {
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!compact && state.objectCount > 0) viewPicker(Modifier)
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
            slice is SliceState.Running -> SliceProgress(slice, state.plateNumber, Modifier.align(Alignment.Center))
            // Placa vazia de um projeto com outras: acrescentar modelo ou trazer objetos de outra.
            state.objectCount == 0 && state.busy == null && state.plates.objects.size > 1 -> EmptyState(
                R.drawable.ic_deployed_code,
                stringResource(R.string.empty_plate_title, state.plateNumber),
                stringResource(R.string.empty_plate_text),
                Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            ) {
                AppButton(stringResource(R.string.add_model), pickers.addModel, icon = R.drawable.ic_add_box)
            }
            state.objectCount == 0 && state.busy == null -> EmptyState(
                R.drawable.ic_deployed_code,
                stringResource(R.string.empty_title),
                stringResource(R.string.empty_text),
                Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            ) {
                AppButton(stringResource(R.string.open_file_short), pickers.openFile, icon = R.drawable.ic_file_open)
                state.recents.firstOrNull()?.let { recent ->
                    AppButton(
                        recent.name, { viewModel.openRecent(recent) }, Modifier.widthIn(max = 200.dp),
                        style = ButtonStyle.OUTLINED, icon = R.drawable.ic_history,
                    )
                }
            }
        }
        BottomControls(state, viewModel, compact, viewPicker)
    }
}

@Composable
private fun BoxScope.BottomControls(state: SlicerUiState, viewModel: SlicerViewModel, compact: Boolean, viewPicker: @Composable (Modifier) -> Unit) {
    val editable = state.editable
    val plates = state.plates
    // No celular o sheet recolhido cobre a faixa de baixo: placas e ações sobem para o topo.
    val bottom = if (compact) Modifier.align(Alignment.TopStart).padding(top = if (state.anyOutside) 110.dp else 0.dp)
    else Modifier.align(Alignment.BottomStart)
    Row(bottom.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // As placas ficam com o espaço que sobra das ações; muitas abas rolam na horizontal.
        Box(Modifier.weight(1f)) {
            if (state.objectCount > 0 || plates.objects.size > 1) PlateControls(plates, viewModel, editable, compact)
        }
        if (state.objectCount > 0) {
            if (compact) viewPicker(Modifier)
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

/**
 * Placas: abas com "+" e excluir no tablet, menu "Placa 1 de 3" no celular. Excluir uma placa
 * com objetos pergunta se eles vão para a vizinha ou são excluídos.
 */
@Composable
private fun PlateControls(plates: Plates, viewModel: SlicerViewModel, enabled: Boolean, compact: Boolean) {
    var deleting by remember { mutableStateOf<Int?>(null) }
    val labels = plates.objects.indices.map { stringResource(R.string.plate_n, it + 1) }
    val delete = { plate: Int -> if (plates.objects[plate] == 0) viewModel.deletePlate(plate, null) else deleting = plate }
    if (compact) {
        var open by remember { mutableStateOf(false) }
        Box {
            FloatingPill(
                stringResource(R.string.plate_of, plates.selected + 1, plates.objects.size), { open = true },
                icon = R.drawable.ic_arrow_drop_down, enabled = enabled,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                labels.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { open = false; viewModel.selectPlate(index) },
                        trailingIcon = if (index == plates.selected) {
                            { Icon(painterResource(R.drawable.ic_check), null) }
                        } else null,
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_plate)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) },
                    onClick = { open = false; viewModel.addPlate() },
                )
                if (plates.objects.size > 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_plate, plates.selected + 1)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        onClick = { open = false; delete(plates.selected) },
                    )
                }
            }
        }
    } else {
        PillTabs(labels, plates.selected, viewModel::selectPlate, enabled = { enabled }) {
            AppIconButton(R.drawable.ic_add, stringResource(R.string.add_plate), viewModel::addPlate, enabled = enabled)
            if (plates.objects.size > 1) {
                AppIconButton(
                    R.drawable.ic_delete, stringResource(R.string.delete_plate, plates.selected + 1), { delete(plates.selected) },
                    enabled = enabled,
                )
            }
        }
    }
    deleting?.let { plate ->
        // A que fica na mesa no lugar dela.
        val neighbor = if (plate > 0) plate - 1 else 1
        val count = plates.objects[plate]
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_plate, plate + 1)) },
            text = { Text(pluralStringResource(R.plurals.delete_plate_objects, count, plate + 1, count)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; viewModel.deletePlate(plate, neighbor) }) {
                    Text(stringResource(R.string.move_to_plate, neighbor + 1))
                }
                TextButton(onClick = { deleting = null; viewModel.deletePlate(plate, null) }) {
                    Text(stringResource(R.string.delete_objects), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ToolButtons(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier = Modifier) {
    for ((tool, icon, label) in TOOLS) {
        ToolButton(icon, stringResource(label), state.tool == tool, { viewModel.selectTool(tool) }, modifier, enabled = state.editable)
    }
}

/** Cartão de progresso do fatiamento, sobre o viewport: porcentagem e etapas. */
@Composable
private fun SliceProgress(slice: SliceState.Running, plate: Int, modifier: Modifier) {
    FloatingCard(modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.slicing_plate, plate), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${slice.percent}%", style = MonoStyles.title, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { slice.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Column {
                for (stage in SliceStage.entries) {
                    val status = when {
                        stage < slice.stage -> StepStatus.DONE
                        stage == slice.stage -> StepStatus.CURRENT
                        else -> StepStatus.PENDING
                    }
                    val layer = slice.layer?.takeIf { stage == slice.stage }?.let { stringResource(R.string.slice_layer, it) }
                    StepRow(stringResource(stage.label), status, detail = layer)
                }
            }
            Text(stringResource(R.string.slice_background_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ProfileSummary(state, viewModel, openParameters)
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
        ProfileSummary(state, viewModel, openParameters)
    }
}

/** Perfis ativos (tocar abre o tipo em Parâmetros) e os filamentos do projeto. */
@Composable
private fun ProfileSummary(state: SlicerUiState, viewModel: SlicerViewModel, openParameters: (PresetKind) -> Unit) {
    SectionLabel(stringResource(R.string.profile))
    val vendor = state.vendors.selected
    val rows = listOf(
        Triple(PresetKind.PRINTER, R.drawable.ic_print, if (vendor != null) stringResource(R.string.printer_from, vendor) else stringResource(R.string.printer)),
        Triple(PresetKind.PROCESS, R.drawable.ic_stacks, stringResource(R.string.process)),
    )
    for ((kind, icon, overline) in rows) {
        val choice = state.presets[kind]
        NavigationRow(
            icon, overline, choice?.selected ?: "—", { openParameters(kind) },
            tag = choice?.values?.dirty?.size?.takeIf { it > 0 }?.let { stringResource(R.string.modified_count, it) },
        )
    }
    FilamentsSection(state, viewModel, state.editable, openParameters)
}

/** Número e cor de um filamento do projeto, com [name] ao lado quando dado. */
@Composable
fun FilamentLabel(index: Int, color: FloatArray?, name: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(12.dp)
                .background(color?.let { Color(it[0], it[1], it[2]) } ?: MaterialTheme.colorScheme.outline, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Text(
            if (name != null) "${index + 1} · $name" else "${index + 1}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
        state.scene.meshes.getOrNull(index)?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.object_n, index + 1),
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        stringResource(R.string.object_size, placement.size[0], placement.size[1], placement.size[2]),
        style = MonoStyles.small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ObjectFilamentField(state, viewModel, index, state.editable)
    // Pintado em várias cores: os filamentos da pintura além do do objeto.
    val used = state.scene.meshes.getOrNull(index)?.filaments?.distinct().orEmpty()
    if (state.filaments.size > 1 && used.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (filament in used) FilamentLabel(filament.toInt(), state.scene.filamentColors.getOrNull(filament.toInt()))
        }
    }
    if (state.plates.objects.size > 1) {
        val plates = state.plates.objects.indices.map { it.toString() }
        SelectField(
            state.plates.selected.toString(), plates, { viewModel.moveToPlate(index, it.toInt()) },
            Modifier.fillMaxWidth(), label = stringResource(R.string.plate),
            labels = state.plates.objects.indices.map { stringResource(R.string.plate_n, it + 1) }, enabled = state.editable,
        )
    }
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
