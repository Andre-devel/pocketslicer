package io.github.andredevel.slicer

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.andredevel.slicer.core.HeightRange
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SettingsTarget
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.NavigationRow
import io.github.andredevel.slicer.ui.components.NumberField
import io.github.andredevel.slicer.ui.components.SectionLabel
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Configuração própria do objeto selecionado, como na lista de objetos do Orca: opções do
 * objeto inteiro e faixas de altura, cada uma com as suas. Tocar abre o editor.
 */
@Composable
fun ObjectSettingsSection(state: SlicerUiState, viewModel: SlicerViewModel, objectIndex: Int, objectHeight: Float) {
    val settings = state.objectSettings.getOrNull(objectIndex) ?: ObjectSettings()
    var editing by remember(objectIndex) { mutableStateOf<SettingsTarget?>(null) }

    SectionLabel(stringResource(R.string.object_settings), Modifier.padding(top = 8.dp))
    NavigationRow(
        R.drawable.ic_tune, stringResource(R.string.settings_whole_object), changedSummary(settings.values),
        { editing = SettingsTarget(objectIndex) }, enabled = state.editable,
    )
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

    editing?.let { target -> SettingsEditor(state, viewModel, target) { editing = null } }
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
 * Editor das opções de [target]: as alteradas, com o valor do processo ao lado (↺ volta a ele),
 * e a busca para acrescentar outra. Numa faixa, também os limites.
 */
@Composable
private fun SettingsEditor(state: SlicerUiState, viewModel: SlicerViewModel, target: SettingsTarget, onDismiss: () -> Unit) {
    val settings = state.objectSettings.getOrNull(target.objectIndex) ?: ObjectSettings()
    val range = if (target.kind == SettingsTarget.Kind.RANGE) settings.ranges.getOrNull(target.index) else null
    val values = if (target.kind == SettingsTarget.Kind.RANGE) range?.values.orEmpty() else settings.values
    val process = state.presets[PresetKind.PROCESS]?.values?.values.orEmpty()
    val allowed = state.settingsKeys[target.kind].orEmpty()
    val defs = state.optionDefs[PresetKind.PROCESS].orEmpty().filter { it.key in allowed && it.key in process }
    val changed = defs.filter { it.key in values }
    var query by rememberSaveable(target) { mutableStateOf("") }
    val trimmed = query.trim()
    val candidates = defs.filter { it.key !in values }.filter { def ->
        if (trimmed.isEmpty()) def.simple else listOf(def.label, def.key).any { it.contains(trimmed, ignoreCase = true) }
    }
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
                            if (range != null) rangeLabel(range.range) else stringResource(R.string.settings_whole_object),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    item(key = "hint") {
                        Text(
                            stringResource(if (range != null) R.string.height_range_hint else R.string.settings_object_hint),
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
                    if (changed.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.settings_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                    items(changed, key = { "changed-${it.key}" }) { def ->
                        OptionRow(
                            def, values[def.key], process[def.key], advanced = false, enabled = enabled,
                            onReset = { viewModel.eraseSetting(target, def.key) },
                        ) { viewModel.setSetting(target, def.key, it) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item(key = "add") {
                        SectionLabel(stringResource(R.string.settings_add_option), Modifier.padding(top = 20.dp, bottom = 8.dp))
                        FormField(
                            query, { query = it }, stringResource(R.string.search_parameters), Modifier.fillMaxWidth(),
                            trailingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                        )
                        if (trimmed.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_suggested), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                            )
                        } else if (candidates.isEmpty()) {
                            Text(stringResource(R.string.settings_no_match), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    items(candidates, key = { "candidate-${it.key}" }) { def ->
                        CandidateRow(def, enabled) {
                            // Começa no valor do processo; o usuário muda em seguida.
                            process[def.key]?.let { value -> viewModel.setSetting(target, def.key, value) }
                            query = ""
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(def: OptionDef, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onAdd).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(def.label, style = MaterialTheme.typography.bodyMedium)
            if (def.category.isNotEmpty()) {
                Text(def.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add), tint = MaterialTheme.colorScheme.primary)
    }
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
