package io.github.andredevel.slicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.OptionType
import io.github.andredevel.slicer.core.PresetKind

/** Filtro do painel de parâmetros: tipo de preset, modo avançado e busca. */
data class ParameterFilter(val kind: PresetKind = PresetKind.PROCESS, val advanced: Boolean = false, val query: String = "")

private val KIND_LABELS = listOf(
    PresetKind.PROCESS to R.string.process,
    PresetKind.FILAMENT to R.string.filament,
    PresetKind.PRINTER to R.string.printer,
)

/**
 * Parâmetros do preset em edição (RF-11/RF-12): no modo básico, as opções do modo simples da
 * GUI do Orca; no avançado ou numa busca, todas. Agrupadas pela categoria do PrintConfigDef.
 */
fun LazyListScope.parameterItems(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    filter: ParameterFilter,
    onFilter: (ParameterFilter) -> Unit,
    enabled: Boolean,
) {
    val choice = state.presets[filter.kind] ?: PresetChoice()
    item(key = "parameters-header") {
        ParametersHeader(choice, viewModel, filter, onFilter, enabled)
    }
    val query = filter.query.trim()
    val visible = state.optionDefs[filter.kind].orEmpty().filter { def ->
        if (query.isEmpty()) {
            filter.advanced || def.simple
        } else {
            listOf(def.label, def.key, def.tooltip).any { it.contains(query, ignoreCase = true) }
        }
    }
    for ((category, defs) in visible.groupBy { it.category.ifEmpty { "-" } }) {
        item(key = "category-${filter.kind}-$category") {
            Text(category, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(defs, key = { "option-${filter.kind}-${it.key}" }) { def ->
            OptionRow(def, choice.values.values[def.key], def.key in choice.values.dirty, enabled) {
                viewModel.setOption(filter.kind, def.key, it)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParametersHeader(
    choice: PresetChoice,
    viewModel: SlicerViewModel,
    filter: ParameterFilter,
    onFilter: (ParameterFilter) -> Unit,
    enabled: Boolean,
) {
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importPreset)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportPreset(filter.kind, it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.parameters), style = MaterialTheme.typography.titleMedium)
        PrimaryTabRow(selectedTabIndex = KIND_LABELS.indexOfFirst { it.first == filter.kind }) {
            for ((kind, label) in KIND_LABELS) {
                Tab(selected = kind == filter.kind, onClick = { onFilter(filter.copy(kind = kind)) },
                    text = { Text(stringResource(label)) })
            }
        }
        Text(
            (choice.selected ?: "") + if (choice.dirty) " " + stringResource(R.string.modified) else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { saving = true }, enabled = enabled) { Text(stringResource(R.string.save_preset)) }
            TextButton(onClick = { deleting = true }, enabled = enabled && choice.selectedIsUser) { Text(stringResource(R.string.delete)) }
            TextButton(onClick = { viewModel.discardChanges(filter.kind) }, enabled = enabled && choice.dirty) {
                Text(stringResource(R.string.discard_changes))
            }
            TextButton(onClick = { import.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = enabled) {
                Text(stringResource(R.string.import_preset))
            }
            TextButton(onClick = { export.launch(viewModel.presetFileName(filter.kind)) }, enabled = enabled) {
                Text(stringResource(R.string.export_preset))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = filter.advanced, onCheckedChange = { onFilter(filter.copy(advanced = it)) })
            Text(stringResource(R.string.advanced_mode), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilter(filter.copy(query = it)) },
            label = { Text(stringResource(R.string.search_parameters)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (saving) {
        // Sobre um preset de usuário, sugere sobrescrevê-lo; sobre um de sistema, uma cópia.
        val suggestion = choice.selected?.let { if (choice.selectedIsUser) it else stringResource(R.string.preset_copy_name, it) }.orEmpty()
        var name by remember { mutableStateOf(suggestion) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(stringResource(R.string.save_preset)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.preset_name)) })
            },
            confirmButton = {
                TextButton(onClick = { saving = false; viewModel.savePreset(filter.kind, name) }, enabled = name.isNotBlank()) {
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
                TextButton(onClick = { deleting = false; choice.selected?.let { viewModel.deletePreset(filter.kind, it) } }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Editor de uma opção conforme o tipo; [onCommit] recebe o valor no formato do Orca. */
@Composable
private fun OptionRow(def: OptionDef, value: String?, dirty: Boolean, enabled: Boolean, onCommit: (String) -> Unit) {
    var showTooltip by remember { mutableStateOf(false) }
    val label = def.label + if (dirty) " •" else ""
    val labelWeight = if (dirty) FontWeight.Bold else null
    // Vetores com mais de um valor (vários extrusores) são editados como texto.
    val single = value != null && !(def.vector && ',' in value)
    Column(Modifier.fillMaxWidth()) {
        when {
            def.type == OptionType.BOOL && single -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = labelWeight, modifier = Modifier.weight(1f).clickable { showTooltip = !showTooltip })
                Switch(checked = value == "1", onCheckedChange = { onCommit(if (it) "1" else "0") }, enabled = enabled)
            }
            def.type == OptionType.ENUM && single && def.enumValues.isNotEmpty() ->
                EnumField(def, label, value!!, enabled, onCommit)
            else -> TextCommitField(def, label, value.orEmpty(), enabled, onCommit)
        }
        if (showTooltip && def.tooltip.isNotEmpty()) {
            Text(def.tooltip, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumField(def: OptionDef, label: String, value: String, enabled: Boolean, onCommit: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = if (def.enumLabels.size == def.enumValues.size) def.enumLabels else def.enumValues
    val current = def.enumValues.indexOf(value).takeIf { it >= 0 }?.let(labels::get) ?: value
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            def.enumValues.forEachIndexed { i, option ->
                DropdownMenuItem(text = { Text(labels[i]) }, onClick = {
                    expanded = false
                    if (option != value) onCommit(option)
                })
            }
        }
    }
}

/** Campo de texto que aplica o valor ao confirmar no teclado ou ao perder o foco. */
@Composable
private fun TextCommitField(def: OptionDef, label: String, value: String, enabled: Boolean, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = when (def.type) {
        OptionType.INT, OptionType.FLOAT -> if (def.vector) KeyboardType.Text else KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        suffix = def.sidetext.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        supportingText = def.tooltip.takeIf { focused && it.isNotEmpty() }?.let { { Text(it) } },
        enabled = enabled,
        singleLine = !def.multiline,
        minLines = if (def.multiline) 3 else 1,
        maxLines = if (def.multiline) 8 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (def.multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            // Vírgula decimal (teclado pt-BR) vira ponto, exceto em listas de vetores.
            if (focused && !it.isFocused && text != value) {
                onCommit(if (keyboard == KeyboardType.Decimal) text.replace(',', '.') else text)
            }
            focused = it.isFocused
        },
    )
}
