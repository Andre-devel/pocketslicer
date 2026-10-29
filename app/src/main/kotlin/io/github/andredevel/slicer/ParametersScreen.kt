package io.github.andredevel.slicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.OptionType
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.ColumnDivider
import io.github.andredevel.slicer.ui.components.CommitField
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.InfoCard
import io.github.andredevel.slicer.ui.components.ScreenHeader
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.SelectField
import io.github.andredevel.slicer.ui.components.StepperField
import io.github.andredevel.slicer.ui.theme.MonoStyles
import kotlinx.coroutines.launch

/** Filtro de Parâmetros: tipo de preset, modo avançado e busca. */
data class ParameterFilter(val kind: PresetKind = PresetKind.PROCESS, val advanced: Boolean = false, val query: String = "")

private val KIND_LABELS = listOf(
    PresetKind.PROCESS to R.string.process,
    PresetKind.FILAMENT to R.string.filament,
    PresetKind.PRINTER to R.string.printer,
)

/** Categoria do PrintConfigDef e as opções visíveis dela. */
private class Category(val name: String, val defs: List<OptionDef>)

/**
 * Parâmetros (RF-11/RF-12): perfil ativo, preset em edição e as opções dele. No modo básico,
 * as opções do modo simples da GUI do Orca; no avançado ou numa busca, todas, agrupadas pela
 * categoria do PrintConfigDef.
 */
@Composable
fun ParametersScreen(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    filter: ParameterFilter,
    onFilter: (ParameterFilter) -> Unit,
    modifier: Modifier,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_DP
    val enabled = state.busy == null && state.slice !is SliceState.Running
    val choice = state.presets[filter.kind] ?: PresetChoice()
    val query = filter.query.trim()
    val defs = state.optionDefs[filter.kind].orEmpty()
    val general = stringResource(R.string.category_general)
    val categories = remember(defs, filter.advanced, query, general) {
        defs.filter { def ->
            if (query.isEmpty()) filter.advanced || def.simple
            else listOf(def.label, def.key, def.tooltip).any { it.contains(query, ignoreCase = true) }
        }.groupBy { it.category.ifEmpty { general } }.map { (name, list) -> Category(name, list) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Row(modifier) {
        if (wide) {
            ActiveProfile(state, viewModel, enabled, Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface))
            ColumnDivider()
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ParametersHeader(choice, viewModel, filter.kind, enabled, compact = !wide)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedTabs(
                    KIND_LABELS.map { (kind, label) -> Segment(stringResource(label), state.presets[kind]?.values?.dirty?.size) },
                    KIND_LABELS.indexOfFirst { it.first == filter.kind },
                    { onFilter(filter.copy(kind = KIND_LABELS[it].first)) },
                    Modifier.fillMaxWidth(),
                )
                if (!wide) {
                    SelectField(
                        choice.selected, choice.options, { viewModel.selectPreset(filter.kind, it) },
                        label = stringResource(KIND_LABELS.first { it.first == filter.kind }.second),
                        enabled = enabled, prefix = if (choice.dirty) "* " else null,
                    )
                }
                val search = @Composable { modifier: Modifier ->
                    FormField(
                        filter.query, { onFilter(filter.copy(query = it)) }, stringResource(R.string.search_parameters),
                        modifier,
                        trailingIcon = {
                            if (filter.query.isEmpty()) Icon(painterResource(R.drawable.ic_search), null)
                            else IconButton(onClick = { onFilter(filter.copy(query = "")) }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear))
                            }
                        },
                    )
                }
                val mode = @Composable { modifier: Modifier ->
                    SegmentedTabs(
                        listOf(Segment(stringResource(R.string.basic)), Segment(stringResource(R.string.advanced))),
                        if (filter.advanced) 1 else 0,
                        { onFilter(filter.copy(advanced = it == 1)) },
                        modifier,
                    )
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        search(Modifier.weight(1f))
                        mode(Modifier.width(260.dp))
                    }
                } else {
                    search(Modifier)
                    mode(Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.weight(1f)) {
                if (wide) {
                    CategoryList(categories, choice, query, defs.size, Modifier.width(200.dp).fillMaxHeight()) { index ->
                        // Cada categoria ocupa um item de título mais um por opção.
                        val position = categories.take(index).sumOf { it.defs.size + 1 }
                        scope.launch { listState.animateScrollToItem(position) }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    for (category in categories) {
                        item(key = "category-${filter.kind}-${category.name}") {
                            SectionLabel(category.name, Modifier.padding(top = 12.dp))
                        }
                        items(category.defs, key = { "option-${filter.kind}-${it.key}" }) { def ->
                            OptionRow(def, choice.values.values[def.key], def.key in choice.values.dirty, filter.advanced, enabled) {
                                viewModel.setOption(filter.kind, def.key, it)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Coluna "Perfil ativo": fabricante e os presets escolhidos. */
@Composable
private fun ActiveProfile(state: SlicerUiState, viewModel: SlicerViewModel, enabled: Boolean, modifier: Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.active_profile))
            SelectField(
                state.vendors.selected, state.vendors.options, viewModel::selectVendor,
                label = stringResource(R.string.vendor), enabled = enabled,
            )
            for ((kind, label) in listOf(PresetKind.PRINTER to R.string.printer, PresetKind.PROCESS to R.string.process, PresetKind.FILAMENT to R.string.filament)) {
                val choice = state.presets[kind]
                SelectField(
                    choice?.selected, choice?.options.orEmpty(), { viewModel.selectPreset(kind, it) },
                    label = stringResource(label), enabled = enabled, prefix = if (choice?.dirty == true) "* " else null,
                )
            }
        }
        InfoCard(stringResource(R.string.bundled_profiles_text), title = stringResource(R.string.bundled_profiles))
    }
}

/** Barra de Parâmetros: descartar, salvar como e o menu com importar/exportar/excluir. */
@Composable
private fun ParametersHeader(choice: PresetChoice, viewModel: SlicerViewModel, kind: PresetKind, enabled: Boolean, compact: Boolean) {
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importPreset) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportPreset(kind, it) }
    }

    ScreenHeader(stringResource(R.string.parameters)) {
        if (!compact) {
            AppButton(
                stringResource(R.string.discard_changes), { viewModel.discardChanges(kind) },
                style = ButtonStyle.TEXT, icon = R.drawable.ic_undo, enabled = enabled && choice.dirty,
            )
            AppButton(stringResource(R.string.save_as), { saving = true }, style = ButtonStyle.TONAL, icon = R.drawable.ic_save_as, enabled = enabled)
        } else {
            AppIconButton(R.drawable.ic_save_as, stringResource(R.string.save_as), { saving = true }, enabled = enabled)
        }
        Box {
            AppIconButton(R.drawable.ic_more_vert, stringResource(R.string.more_options), { menu = true }, tonal = menu)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (compact) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.discard_changes)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_undo), null) },
                        enabled = enabled && choice.dirty,
                        onClick = { menu = false; viewModel.discardChanges(kind) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.import_preset)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_file_download), null) },
                    enabled = enabled,
                    onClick = { menu = false; import.launch(arrayOf("application/json", "application/octet-stream")) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_preset)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_file_upload), null) },
                    enabled = enabled,
                    onClick = { menu = false; export.launch(viewModel.presetFileName(kind)) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.delete_user_preset))
                            if (!choice.selectedIsUser) {
                                Text(stringResource(R.string.system_preset_locked), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                    enabled = enabled && choice.selectedIsUser,
                    onClick = { menu = false; deleting = true },
                )
            }
        }
    }

    if (saving) {
        // Sobre um preset de usuário, sugere sobrescrevê-lo; sobre um de sistema, uma cópia.
        val suggestion = choice.selected?.let { if (choice.selectedIsUser) it else stringResource(R.string.preset_copy_name, it) }.orEmpty()
        var name by remember { mutableStateOf(suggestion) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(stringResource(R.string.save_preset)) },
            text = { FormField(name, { name = it }, stringResource(R.string.preset_name)) },
            confirmButton = {
                TextButton(onClick = { saving = false; viewModel.savePreset(kind, name) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_preset_title)) },
            text = { Text(choice.selected.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { deleting = false; choice.selected?.let { viewModel.deletePreset(kind, it) } }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Categorias com a quantidade de opções e um ponto quando há alteração; tocar rola até ela. */
@Composable
private fun CategoryList(categories: List<Category>, choice: PresetChoice, query: String, total: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        categories.forEachIndexed { index, category ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(category.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (category.defs.any { it.key in choice.values.dirty }) DirtyDot()
                Text("${category.defs.size}", style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (query.isNotEmpty()) {
            Text(
                stringResource(R.string.search_results, categories.sumOf { it.defs.size }, query, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun DirtyDot() = Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))

/**
 * Uma opção: rótulo (tocar mostra a dica), chave do Orca no modo avançado e o editor conforme
 * o tipo; [onCommit] recebe o valor no formato do Orca.
 */
@Composable
private fun OptionRow(def: OptionDef, value: String?, dirty: Boolean, advanced: Boolean, enabled: Boolean, onCommit: (String) -> Unit) {
    var showTooltip by remember { mutableStateOf(false) }
    // Vetores com mais de um valor (vários extrusores) são editados como texto.
    val single = value != null && !(def.vector && ',' in value)
    val multiline = def.multiline || (def.type == OptionType.STRING && def.vector)
    val label = @Composable { modifier: Modifier ->
        Column(modifier.clickable(enabled = def.tooltip.isNotEmpty()) { showTooltip = !showTooltip }.padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dirty) DirtyDot()
                Text(def.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = MaterialTheme.typography.labelLarge.fontWeight))
            }
            if (advanced) Text(def.key, style = MonoStyles.small, color = MaterialTheme.colorScheme.outline)
            if (showTooltip) {
                Text(def.tooltip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (multiline || !single) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            label(Modifier.fillMaxWidth())
            CommitField(value.orEmpty(), onCommit, Modifier.fillMaxWidth(), enabled = enabled, multiline = multiline, suffix = def.sidetext.ifEmpty { null })
        }
        return
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        label(Modifier.weight(1f))
        val editor = Modifier.width(EDITOR_WIDTH)
        when {
            def.type == OptionType.BOOL -> Switch(checked = value == "1", onCheckedChange = { onCommit(if (it) "1" else "0") }, enabled = enabled)
            def.type == OptionType.ENUM && def.enumValues.isNotEmpty() -> SelectField(
                value, def.enumValues, onCommit, editor,
                labels = if (def.enumLabels.size == def.enumValues.size) def.enumLabels else def.enumValues, enabled = enabled,
            )
            def.type == OptionType.INT && value.toIntOrNull() != null -> StepperField(value.toInt(), { onCommit("$it") }, editor, enabled = enabled)
            else -> {
                val numeric = def.type in NUMERIC_TYPES
                CommitField(
                    value,
                    // Vírgula decimal (teclado pt-BR) vira ponto.
                    { onCommit(if (numeric) it.replace(',', '.') else it) },
                    editor,
                    suffix = def.sidetext.ifEmpty { null },
                    enabled = enabled,
                    keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                )
            }
        }
    }
}

private val NUMERIC_TYPES = setOf(OptionType.INT, OptionType.FLOAT, OptionType.PERCENT, OptionType.FLOAT_OR_PERCENT)
private val EDITOR_WIDTH = 180.dp
private const val WIDE_DP = 840
