package io.github.andredevel.slicer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.andredevel.slicer.core.HeightRange
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.Part
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SettingsTarget
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.IconTab
import io.github.andredevel.slicer.ui.components.IconTabs
import io.github.andredevel.slicer.ui.components.LocalFieldHeight
import io.github.andredevel.slicer.ui.components.NavigationRow
import io.github.andredevel.slicer.ui.components.NumberField
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.SelectField
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Configuração própria do objeto selecionado, como o modo Objeto do Creality Print: as páginas
 * do processo em abas logo no painel, e as faixas de altura (tocar abre o editor delas).
 */
@Composable
fun ObjectSettingsSection(state: SlicerUiState, viewModel: SlicerViewModel, objectIndex: Int, objectHeight: Float) {
    val settings = state.objectSettings.getOrNull(objectIndex) ?: ObjectSettings()
    var editing by remember(objectIndex) { mutableStateOf<SettingsTarget?>(null) }
    val target = SettingsTarget(objectIndex)
    var query by rememberSaveable(target) { mutableStateOf("") }
    var advanced by rememberSaveable(target) { mutableStateOf(false) }
    var page by rememberSaveable(target) { mutableStateOf("") }
    val pages = settingsPages(state, target, query, advanced, page)

    SectionLabel(stringResource(R.string.object_settings), Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.settings_object_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PageControls(pages, query, { query = it }, advanced, { advanced = it }, { page = it }, stacked = true)
    CompositionLocalProvider(LocalFieldHeight provides DENSE_FIELD_HEIGHT) {
        Column {
            if (pages.pages.isEmpty()) {
                Text(stringResource(R.string.settings_no_match), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }
            pages.shown.forEachIndexed { index, def -> PageOption(state, viewModel, target, pages, index, def, dense = true) }
        }
    }
    settings.ranges.forEachIndexed { index, range ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavigationRow(
                R.drawable.ic_layers, rangeLabel(range.range), changedSummary(range.values),
                { editing = SettingsTarget(objectIndex, SettingsTarget.Kind.RANGE, index) },
                Modifier.weight(1f), enabled = state.editable,
            )
            AppIconButton(
                R.drawable.ic_delete, stringResource(R.string.delete_height_range), { viewModel.removeHeightRange(objectIndex, index) },
                enabled = state.editable, tint = MaterialTheme.colorScheme.error,
            )
        }
    }
    AppButton(
        stringResource(R.string.add_height_range),
        {
            // Como a GUI do Orca: 2 mm logo acima da última faixa, sem passar do topo do objeto.
            val start = settings.ranges.lastOrNull()?.range?.max ?: 0.0
            val end = minOf(start + 2.0, objectHeight.toDouble()).takeIf { it > start } ?: (start + 2.0)
            val range = HeightRange(start, end)
            viewModel.addHeightRange(objectIndex, range)
            editing = SettingsTarget(objectIndex, SettingsTarget.Kind.RANGE, settings.ranges.count { it.range.min < start })
        },
        style = ButtonStyle.TEXT, icon = R.drawable.ic_add, enabled = state.editable,
    )


    // Peças: com só a do objeto, a lista não aparece (como no Orca).
    val parts = state.scene?.parts?.getOrNull(objectIndex).orEmpty()
    var adding by remember(objectIndex) { mutableStateOf(false) }
    SectionLabel(stringResource(R.string.parts), Modifier.padding(top = 8.dp))
    if (parts.size > 1) {
        parts.forEachIndexed { index, part ->
            NavigationRow(
                partIcon(part.type), partTypeLabel(part.type), part.name.ifBlank { partTypeLabel(part.type) },
                { viewModel.select(objectIndex, index) }, enabled = state.editable,
            )
        }
    }
    AppButton(stringResource(R.string.add_part), { adding = true }, style = ButtonStyle.TEXT, icon = R.drawable.ic_add, enabled = state.editable)

    editing?.let { target -> SettingsEditor(state, viewModel, target) { editing = null } }
    if (adding) {
        AddPartDialog(
            { adding = false },
            onAdd = { type, shape -> viewModel.addPart(objectIndex, type, shape) },
            onAddFile = { type, uri -> viewModel.addPartFromFile(objectIndex, type, uri) },
        )
    }
}

/** Peça [partIndex] do objeto selecionado: tipo, posição na do objeto, configuração e exclusão. */
@Composable
fun SelectedPart(state: SlicerUiState, viewModel: SlicerViewModel, objectIndex: Int, partIndex: Int) {
    val parts = state.scene?.parts?.getOrNull(objectIndex).orEmpty()
    val part = parts.getOrNull(partIndex) ?: return
    val values = state.objectSettings.getOrNull(objectIndex)?.parts?.getOrNull(partIndex).orEmpty()
    var editing by remember(objectIndex, partIndex) { mutableStateOf(false) }
    // A última peça do objeto não sai (como no Orca).
    val removable = part.type != Part.Type.MODEL || parts.count { it.type == Part.Type.MODEL } > 1

    SectionLabel(stringResource(R.string.part_title)) {
        AppIconButton(
            R.drawable.ic_delete, stringResource(R.string.delete_part), { viewModel.removePart(objectIndex, partIndex) },
            enabled = state.editable && removable, tint = MaterialTheme.colorScheme.error,
        )
        AppIconButton(R.drawable.ic_close, stringResource(R.string.back_to_object), { viewModel.select(objectIndex) })
    }
    Text(part.name.ifBlank { partTypeLabel(part.type) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        partTypeLabel(part.type) + " · " + stringResource(R.string.object_size, part.placement.size[0], part.placement.size[1], part.placement.size[2]),
        style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Como no Orca, a última peça do objeto não muda de tipo.
    SelectField(
        part.type.name, PART_TYPES.map { it.name }, { viewModel.setPartType(objectIndex, partIndex, Part.Type.valueOf(it)) },
        Modifier.fillMaxWidth().padding(top = 8.dp), label = stringResource(R.string.part_type), labels = PART_TYPES.map { partTypeLabel(it) },
        enabled = state.editable && removable,
    )
    partHint(part.type)?.let {
        Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.part_position_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PlacementFields(part.placement, state.editable, positionAxes = 3) { offset, rotation, scale ->
        viewModel.setPartPlacement(objectIndex, partIndex, offset, rotation, scale)
    }
    if (part.hasSettings) {
        NavigationRow(
            R.drawable.ic_tune, stringResource(R.string.part_settings), changedSummary(values), { editing = true }, enabled = state.editable,
        )
    }
    if (editing) SettingsEditor(state, viewModel, SettingsTarget(objectIndex, SettingsTarget.Kind.VOLUME, partIndex)) { editing = false }
}

/** Tipos na ordem do menu, modificador primeiro, como no Orca. */
private val PART_TYPES = listOf(Part.Type.MODIFIER, Part.Type.NEGATIVE, Part.Type.SUPPORT_BLOCKER, Part.Type.SUPPORT_ENFORCER, Part.Type.MODEL)

/** Tipo e forma da peça nova; tocar numa forma acrescenta, ou escolher um arquivo com a malha. */
@Composable
private fun AddPartDialog(onDismiss: () -> Unit, onAdd: (Part.Type, Part.Shape) -> Unit, onAddFile: (Part.Type, Uri) -> Unit) {
    val types = PART_TYPES
    var type by rememberSaveable { mutableStateOf(Part.Type.MODIFIER) }
    val typeLabels = types.map { partTypeLabel(it) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onDismiss()
            onAddFile(type, uri)
        }
    }
    val shapeLabels = mapOf(
        Part.Shape.CUBE to R.string.part_shape_cube, Part.Shape.CYLINDER to R.string.part_shape_cylinder,
        Part.Shape.SPHERE to R.string.part_shape_sphere, Part.Shape.CONE to R.string.part_shape_cone,
        Part.Shape.DISC to R.string.part_shape_disc, Part.Shape.TORUS to R.string.part_shape_torus,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_part)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    type.name, types.map { it.name }, { type = Part.Type.valueOf(it) },
                    Modifier.fillMaxWidth(), label = stringResource(R.string.part_type), labels = typeLabels,
                )
                Text(stringResource(R.string.add_part_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.part_shape), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                val row = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                for (shape in Part.Shape.entries) {
                    Text(
                        stringResource(shapeLabels.getValue(shape)), style = MaterialTheme.typography.bodyLarge,
                        modifier = row.clickable { onDismiss(); onAdd(type, shape) }.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
                Row(
                    row.clickable { pick.launch(arrayOf("*/*")) }.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_file_open), null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.part_from_file), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun partTypeLabel(type: Part.Type) = stringResource(
    when (type) {
        Part.Type.MODEL -> R.string.part_type_model
        Part.Type.NEGATIVE -> R.string.part_type_negative
        Part.Type.MODIFIER -> R.string.part_type_modifier
        Part.Type.SUPPORT_BLOCKER -> R.string.part_type_support_blocker
        Part.Type.SUPPORT_ENFORCER -> R.string.part_type_support_enforcer
    },
)

private fun partIcon(type: Part.Type) = when (type) {
    Part.Type.MODEL -> R.drawable.ic_deployed_code
    Part.Type.NEGATIVE -> R.drawable.ic_remove
    Part.Type.MODIFIER -> R.drawable.ic_tune
    Part.Type.SUPPORT_BLOCKER -> R.drawable.ic_block
    Part.Type.SUPPORT_ENFORCER -> R.drawable.ic_add_box
}

private fun partHint(type: Part.Type) = when (type) {
    Part.Type.NEGATIVE -> R.string.part_hint_negative
    Part.Type.SUPPORT_BLOCKER -> R.string.part_hint_support_blocker
    Part.Type.SUPPORT_ENFORCER -> R.string.part_hint_support_enforcer
    else -> null
}

@Composable
private fun changedSummary(values: Map<String, String>) =
    if (values.isEmpty()) stringResource(R.string.settings_same_as_process) else stringResource(R.string.settings_changed_count, values.size)

@Composable
private fun rangeLabel(range: HeightRange) = stringResource(R.string.height_range_value, mm(range.min), mm(range.max))

/** Float do campo em mm com até três casas (sem o ruído da conversão para Double). */
private fun Float.toMm(): Double = (toDouble() * 1000).roundToLong() / 1000.0

/** Até duas casas, sem zeros à direita, no idioma do aparelho. */
private fun mm(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd(',', '.')

/**
 * Editor das opções de [target], como o modo Objeto do Creality Print: uma aba com ícone por
 * página do processo e as opções dela com o valor que vale ali. Mudar um valor o substitui só
 * no alvo; ↺ volta ao do processo. Numa faixa, também os limites.
 */
@Composable
internal fun SettingsEditor(state: SlicerUiState, viewModel: SlicerViewModel, target: SettingsTarget, onDismiss: () -> Unit) {
    val settings = state.objectSettings.getOrNull(target.objectIndex) ?: ObjectSettings()
    val range = if (target.kind == SettingsTarget.Kind.RANGE) settings.ranges.getOrNull(target.index) else null
    val part = if (target.kind == SettingsTarget.Kind.VOLUME) state.scene?.parts?.getOrNull(target.objectIndex)?.getOrNull(target.index) else null
    var query by rememberSaveable(target) { mutableStateOf("") }
    var advanced by rememberSaveable(target) { mutableStateOf(false) }
    var page by rememberSaveable(target) { mutableStateOf("") }
    val pages = settingsPages(state, target, query, advanced, page)
    // Uma posição de rolagem por aba, como em Parâmetros.
    val listStates = remember(target) { mutableMapOf<String, LazyListState>() }
    val listState = listStates.getOrPut(pages.pages.getOrNull(pages.selected)?.first.orEmpty()) { LazyListState() }
    val enabled = state.editable

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().widthIn(max = 720.dp).imePadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.scene?.meshes?.getOrNull(target.objectIndex)?.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.object_n, target.objectIndex + 1),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        Text(
                            when {
                                range != null -> rangeLabel(range.range)
                                part != null -> part.name.ifBlank { partTypeLabel(part.type) }
                                else -> stringResource(R.string.settings_whole_object)
                            },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PageControls(pages, query, { query = it }, advanced, { advanced = it }, { page = it }, stacked = false)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                    item(key = "hint") {
                        Text(
                            stringResource(
                                when (target.kind) {
                                    SettingsTarget.Kind.OBJECT -> R.string.settings_object_hint
                                    SettingsTarget.Kind.RANGE -> R.string.height_range_hint
                                    SettingsTarget.Kind.VOLUME -> R.string.settings_part_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (range != null) {
                        item(key = "bounds") {
                            RangeBounds(range.range, settings.ranges.map { it.range } - range.range, enabled) {
                                viewModel.setHeightRange(target.objectIndex, target.index, it)
                            }
                        }
                    }
                    if (pages.pages.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.settings_no_match), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                    itemsIndexed(pages.shown, key = { _, def -> "option-${def.key}" }) { index, def ->
                        PageOption(state, viewModel, target, pages, index, def, dense = false)
                    }
                }
            }
        }
    }
}

/** Opções de um alvo divididas nas páginas do processo; [shown]: as da aba escolhida. */
private class SettingsPages(
    val pages: List<Pair<String, List<OptionDef>>>,
    val selected: Int,
    val values: Map<String, String>,
    val process: Map<String, String>,
) {
    val shown: List<OptionDef> get() = pages.getOrNull(selected)?.second.orEmpty()
}

/**
 * Páginas de [target]: no básico, o que a GUI do Orca mostra no modo simples (as alteradas
 * sempre aparecem); numa busca, as que casam com ela. [page] é a aba escolhida.
 */
private fun settingsPages(state: SlicerUiState, target: SettingsTarget, query: String, advanced: Boolean, page: String): SettingsPages {
    val settings = state.objectSettings.getOrNull(target.objectIndex) ?: ObjectSettings()
    val values = when (target.kind) {
        SettingsTarget.Kind.OBJECT -> settings.values
        SettingsTarget.Kind.RANGE -> settings.ranges.getOrNull(target.index)?.values.orEmpty()
        SettingsTarget.Kind.VOLUME -> settings.parts.getOrNull(target.index).orEmpty()
    }
    val process = state.presets[PresetKind.PROCESS]?.values?.values.orEmpty()
    val allowed = state.settingsKeys[target.kind].orEmpty()
    val trimmed = query.trim()
    val pages = state.optionDefs[PresetKind.PROCESS].orEmpty().filter { it.key in allowed && it.key in process }.filter { def ->
        if (trimmed.isNotEmpty()) listOf(def.label, def.key, def.tooltip).any { it.contains(trimmed, ignoreCase = true) }
        else def.key in values || advanced || (def.simple && def.group.isNotEmpty())
    }.groupBy { it.page.ifEmpty { OTHERS_PAGE } }.toList()
    return SettingsPages(pages, pages.indexOfFirst { it.first == page }.takeIf { it >= 0 } ?: 0, values, process)
}

/** Busca, Básico/Avançado e as abas; [stacked]: um embaixo do outro (painel estreito). */
@Composable
private fun PageControls(
    pages: SettingsPages,
    query: String,
    onQuery: (String) -> Unit,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    onPage: (String) -> Unit,
    stacked: Boolean,
) {
    val search = @Composable { modifier: Modifier ->
        FormField(
            query, onQuery, stringResource(R.string.search_parameters), modifier,
            trailingIcon = {
                if (query.isEmpty()) Icon(painterResource(R.drawable.ic_search), null)
                else IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear)) }
            },
        )
    }
    val mode = @Composable { modifier: Modifier ->
        SegmentedTabs(
            listOf(Segment(stringResource(R.string.basic)), Segment(stringResource(R.string.advanced))),
            if (advanced) 1 else 0, { onAdvanced(it == 1) }, modifier, check = false, compact = true,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stacked) {
            mode(Modifier.fillMaxWidth())
            search(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                search(Modifier.weight(1f))
                mode(Modifier.widthIn(max = 220.dp))
            }
        }
        val general = stringResource(R.string.category_general)
        IconTabs(
            pages.pages.map { (key, list) ->
                // Rótulo traduzido da página; as opções sem página vêm depois das dela.
                IconTab(pageIcon(key), list.firstOrNull { it.category.isNotEmpty() }?.category ?: general, list.count { it.key in pages.values })
            },
            pages.selected, { onPage(pages.pages[it].first) }, Modifier.fillMaxWidth(),
        )
    }
}

/** Opção [index] da aba: título do grupo do Orca acima da primeira do grupo, e o editor. */
@Composable
private fun PageOption(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    target: SettingsTarget,
    pages: SettingsPages,
    index: Int,
    def: OptionDef,
    dense: Boolean,
) {
    val shown = pages.shown
    if (def.group.isNotEmpty() && def.group != shown.getOrNull(index - 1)?.group) {
        Text(
            def.group, style = if (dense) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (index == 0) 8.dp else if (dense) 10.dp else 16.dp, bottom = 2.dp),
        )
    }
    val own = pages.values[def.key]
    OptionRow(
        def, own ?: pages.process[def.key], pages.process[def.key].takeIf { own != null }, advanced = false, enabled = state.editable,
        onReset = { viewModel.eraseSetting(target, def.key) },
        dense = dense,
        savedLabel = R.string.process_value,
    ) { viewModel.setSetting(target, def.key, it) }
    if (!dense) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Página do Orca que recebe também as opções sem página (fora da GUI do Orca). */
private const val OTHERS_PAGE = "Others"

/** Ícone da página do processo no Orca ([OptionDef.page]). */
private fun pageIcon(page: String) = when (page) {
    "Quality" -> R.drawable.ic_diamond
    "Strength" -> R.drawable.ic_fitness_center
    "Speed" -> R.drawable.ic_speed
    "Support" -> R.drawable.ic_foundation
    "Multimaterial" -> R.drawable.ic_palette
    OTHERS_PAGE -> R.drawable.ic_more_horiz
    else -> R.drawable.ic_tune
}

/** Limites da faixa; um valor que inverte a faixa ou cruza outra ([others]) não é aplicado. */
@Composable
private fun RangeBounds(range: HeightRange, others: List<HeightRange>, enabled: Boolean, onCommit: (HeightRange) -> Unit) {
    var error by remember(range) { mutableStateOf<String?>(null) }
    val orderError = stringResource(R.string.height_range_error_order)
    val overlap = others.associateWith { rangeLabel(it) }
    val overlapError = @Composable { label: String -> stringResource(R.string.height_range_error_overlap, label) }
    var overlapping by remember(range) { mutableStateOf<HeightRange?>(null) }
    fun commit(candidate: HeightRange) {
        val crossed = others.firstOrNull { candidate.min < it.max && it.min < candidate.max }
        when {
            candidate.min < 0 || candidate.min >= candidate.max -> { error = orderError; overlapping = null }
            crossed != null -> { error = null; overlapping = crossed }
            else -> { error = null; overlapping = null; onCommit(candidate) }
        }
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.height_range_from), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField(range.min.toFloat(), { commit(range.copy(min = it.toMm())) }, Modifier.fillMaxWidth(), suffix = "mm", min = 0f, enabled = enabled)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.height_range_to), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField(range.max.toFloat(), { commit(range.copy(max = it.toMm())) }, Modifier.fillMaxWidth(), suffix = "mm", min = 0f, enabled = enabled)
            }
        }
        val message = error ?: overlapping?.let { overlapError(overlap.getValue(it)) }
        if (message != null) {
            Text(
                message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(8.dp),
            )
        }
    }
}
