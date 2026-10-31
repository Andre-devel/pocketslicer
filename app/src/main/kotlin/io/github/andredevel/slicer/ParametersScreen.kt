package io.github.andredevel.slicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.OptionType
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.ColumnDivider
import io.github.andredevel.slicer.ui.components.CommitField
import io.github.andredevel.slicer.ui.components.CountBadge
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.InfoCard
import io.github.andredevel.slicer.ui.components.LocalFieldHeight
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

/** Páginas com as opções de [defs] que o modo ([advanced]) e a busca ([query]) mostram. */
private fun categories(defs: List<OptionDef>, advanced: Boolean, query: String, general: String): List<Category> =
    defs.filter { def ->
        // No básico, só o que a GUI do Orca mostra (opções sem grupo ficam no avançado).
        if (query.isEmpty()) advanced || (def.simple && def.group.isNotEmpty())
        else listOf(def.label, def.key, def.tooltip).any { it.contains(query, ignoreCase = true) }
    }.groupBy { it.category.ifEmpty { general } }.map { (name, list) -> Category(name, list) }

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
    val categories = remember(defs, filter.advanced, query, general) { categories(defs, filter.advanced, query, general) }
    // Uma posição de rolagem por aba: com uma só, a aba nova abria na posição da anterior
    // (muitas vezes já no fim da lista, onde arrastar para cima não faz nada).
    val listStates = remember { mutableMapOf<PresetKind, LazyListState>() }
    val listState = listStates.getOrPut(filter.kind) { LazyListState() }
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
                // No tablet, linhas densas numa coluna estreita: o campo fica perto do nome.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        Modifier.fillMaxHeight().then(if (wide) Modifier.widthIn(max = LIST_MAX_WIDTH) else Modifier.fillMaxWidth()),
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    ) {
                        for (category in categories) {
                            item(key = "category-${filter.kind}-${category.name}") {
                                SectionLabel(category.name, Modifier.padding(top = 12.dp))
                            }
                            itemsIndexed(category.defs, key = { _, def -> "option-${filter.kind}-${def.key}" }) { index, def ->
                                // Grupo da aba do Orca ("Print temperature"...) acima da primeira opção dele.
                                GroupTitle(category.defs, index, if (wide) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall, if (wide) 10.dp else 16.dp)
                                val saved = choice.values.saved[def.key]?.takeIf { def.key in choice.values.dirty }
                                CompositionLocalProvider(LocalFieldHeight provides if (wide) DENSE_FIELD_HEIGHT else LocalFieldHeight.current) {
                                    OptionRow(
                                        def, choice.values.values[def.key], saved, filter.advanced, enabled,
                                        onReset = { saved?.let { viewModel.setOption(filter.kind, def.key, it) } },
                                        fallback = choice.values.fallback[def.key],
                                        dense = wide,
                                    ) {
                                        viewModel.setOption(filter.kind, def.key, it)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
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

/** Categorias com a quantidade de opções e quantas foram alteradas; tocar rola até ela. */
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
                val changed = category.defs.count { it.key in choice.values.dirty }
                if (changed > 0) CountBadge(changed)
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
 * o tipo; [onCommit] recebe o valor no formato do Orca. [saved]: valor no preset salvo quando a
 * opção foi alterada, mostrado com ↺ ([onReset]) para voltar a ele; [savedLabel] diz de onde ele vem.
 */
@Composable
internal fun OptionRow(
    def: OptionDef,
    value: String?,
    saved: String?,
    advanced: Boolean,
    enabled: Boolean,
    onReset: () -> Unit,
    fallback: String? = null,
    dense: Boolean = false,
    @StringRes savedLabel: Int = R.string.profile_value,
    onCommit: (String) -> Unit,
) {
    val dirty = saved != null
    // Substituição do filamento (como a caixa da página Setting Overrides do Orca): desligada, o
    // valor é "nil" e o editor mostra, apagado, o valor da impressora/processo que vale no lugar.
    val override = def.nullable && fallback != null
    val overridden = !override || value != NIL
    // Lista de texto com um só valor vazio: o Orca serializa como "" entre aspas, que aqui fica
    // como campo vazio (e vazio volta a ser "").
    val stringList = def.type == OptionType.STRING && def.vector
    @Suppress("NAME_SHADOWING")
    val value = (if (overridden) value else fallback)?.let { if (stringList && it == EMPTY_STRING_LIST) "" else it }
    @Suppress("NAME_SHADOWING")
    val onCommit = { text: String -> onCommit(if (stringList && text.isEmpty()) EMPTY_STRING_LIST else text) }
    var showTooltip by remember { mutableStateOf(false) }
    // Vetores com mais de um valor (vários extrusores) são editados como texto.
    val single = value != null && !(def.vector && ',' in value)
    // Listas de texto com um só valor curto (o tipo do filamento) cabem numa linha.
    val multiline = def.multiline || (def.type == OptionType.STRING && def.vector && value != null && (';' in value || '\n' in value))
    val label = @Composable { modifier: Modifier ->
        Column(modifier.clickable(enabled = def.tooltip.isNotEmpty()) { showTooltip = !showTooltip }.padding(vertical = if (dense) 2.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 8.dp)) {
                if (dirty) DirtyDot()
                Text(
                    def.label,
                    style = if (dense) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium.copy(fontWeight = MaterialTheme.typography.labelLarge.fontWeight),
                )
            }
            if (advanced) Text(def.key, style = MonoStyles.small, color = MaterialTheme.colorScheme.outline)
            if (saved != null) {
                Text(
                    stringResource(savedLabel, if (saved == NIL) stringResource(R.string.override_off) else displayValue(def, saved)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showTooltip) {
                Text(def.tooltip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    val reset = @Composable {
        if (dirty) AppIconButton(R.drawable.ic_restart_alt, stringResource(R.string.reset_option), onReset, enabled = enabled)
    }
    if (multiline || !single) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                label(Modifier.weight(1f))
                reset()
            }
            CommitField(value.orEmpty(), onCommit, Modifier.fillMaxWidth(), enabled = enabled, multiline = multiline, suffix = def.sidetext.ifEmpty { null })
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (dense) 40.dp else 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 12.dp),
    ) {
        label(Modifier.weight(1f))
        reset()
        if (override) {
            val description = stringResource(R.string.override_option, def.label)
            Checkbox(
                checked = overridden,
                onCheckedChange = { onCommit(if (it) fallback!! else NIL) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
        val editor = Modifier.width(if (dense) DENSE_EDITOR_WIDTH else EDITOR_WIDTH)
        @Suppress("NAME_SHADOWING")
        val enabled = enabled && overridden
        when {
            def.type == OptionType.BOOL -> Switch(
                checked = value == "1",
                onCheckedChange = { onCommit(if (it) "1" else "0") },
                enabled = enabled,
                modifier = if (dense) Modifier.scale(0.8f) else Modifier,
            )
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

/** Valor salvo como o editor mostraria: rótulo da opção de lista, Sim/Não, número com o sufixo. */
@Composable
private fun displayValue(def: OptionDef, value: String): String = when {
    def.type == OptionType.BOOL && (value == "1" || value == "0") -> stringResource(if (value == "1") R.string.yes else R.string.no)
    def.type == OptionType.ENUM -> def.enumValues.indexOf(value).takeIf { it >= 0 }?.let(def.enumLabels::getOrNull) ?: value
    def.sidetext.isNotEmpty() && def.type in NUMERIC_TYPES -> "$value ${def.sidetext}"
    else -> value
}.replace('\n', ' ')

/** Valor de uma opção que aceita nulo quando não substitui nada. */
private const val NIL = "nil"
private const val EMPTY_STRING_LIST = "\"\""
private val NUMERIC_TYPES = setOf(OptionType.INT, OptionType.FLOAT, OptionType.PERCENT, OptionType.FLOAT_OR_PERCENT)
private val EDITOR_WIDTH = 180.dp
private val DENSE_EDITOR_WIDTH = 144.dp
private val LIST_MAX_WIDTH = 560.dp
private const val WIDE_DP = 840

/** Título do grupo da aba do Orca ("Print temperature"...) acima da primeira opção dele em [defs]. */
@Composable
private fun GroupTitle(defs: List<OptionDef>, index: Int, style: TextStyle, spacing: Dp) {
    val def = defs[index]
    if (def.group.isNotEmpty() && def.group != defs.getOrNull(index - 1)?.group) {
        Text(
            def.group,
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (index == 0) 0.dp else spacing, bottom = 2.dp),
        )
    }
}

/**
 * Parâmetros do processo no painel lateral do Preparar, como a barra lateral do Orca: uma página
 * por vez, linhas densas com o editor ao lado do nome. A tela Parâmetros continua com a lista toda.
 */
@Composable
fun ProcessParametersSection(state: SlicerUiState, viewModel: SlicerViewModel, openParameters: (PresetKind) -> Unit) {
    val kind = PresetKind.PROCESS
    val enabled = state.busy == null && state.slice !is SliceState.Running
    val choice = state.presets[kind] ?: PresetChoice()
    val defs = state.optionDefs[kind].orEmpty()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("") }
    val general = stringResource(R.string.category_general)
    val categories = remember(defs, advanced, general) { categories(defs, advanced, "", general) }
    // A página escolhida; a primeira se ela não existe no modo atual.
    val category = categories.find { it.name == page } ?: categories.firstOrNull()

    SectionLabel(stringResource(R.string.process_parameters)) {
        AppIconButton(R.drawable.ic_open_in_full, stringResource(R.string.all_parameters), { openParameters(kind) })
    }
    SegmentedTabs(
        listOf(Segment(stringResource(R.string.basic)), Segment(stringResource(R.string.advanced))),
        if (advanced) 1 else 0,
        { advanced = it == 1 },
        Modifier.fillMaxWidth(),
        compact = true,
    )
    CompositionLocalProvider(LocalFieldHeight provides DENSE_FIELD_HEIGHT) {
        SelectField(category?.name, categories.map { it.name }, { page = it }, Modifier.fillMaxWidth(), enabled = categories.isNotEmpty())
        Column {
            category?.defs?.forEachIndexed { index, def ->
                GroupTitle(category.defs, index, MaterialTheme.typography.labelLarge, 10.dp)
                val saved = choice.values.saved[def.key]?.takeIf { def.key in choice.values.dirty }
                OptionRow(
                    def, choice.values.values[def.key], saved, advanced = false, enabled = enabled,
                    onReset = { saved?.let { viewModel.setOption(kind, def.key, it) } },
                    fallback = choice.values.fallback[def.key],
                    dense = true,
                ) { viewModel.setOption(kind, def.key, it) }
            }
        }
    }
}

internal val DENSE_FIELD_HEIGHT = 34.dp

