package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.LayerChange
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SelectField
import io.github.andredevel.slicer.ui.theme.MonoStyles
import java.util.Locale

/** Ações por camada da placa (pausa, troca de filamento, G-code), com excluir. */
@Composable
fun LayerChangesSection(state: SlicerUiState, viewModel: SlicerViewModel) {
    if (state.layerChanges.isEmpty()) return
    SectionLabel(stringResource(R.string.layer_changes))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (change in state.layerChanges) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.getDefault(), "%.2f mm", change.z), style = MonoStyles.small, modifier = Modifier.padding(end = 10.dp))
                Text(
                    describe(state, change),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AppIconButton(
                    R.drawable.ic_close, stringResource(R.string.remove_layer_change), { viewModel.removeLayerChange(change) },
                    enabled = state.busy == null,
                )
            }
        }
    }
}

@Composable
private fun describe(state: SlicerUiState, change: LayerChange): String = when (change.action) {
    LayerChange.Action.PAUSE -> stringResource(R.string.layer_pause)
    LayerChange.Action.FILAMENT_CHANGE -> stringResource(
        R.string.layer_filament_change_to,
        "${change.filament + 1} · ${state.filaments.getOrElse(change.filament) { "?" }}",
    )
    LayerChange.Action.CUSTOM -> stringResource(R.string.layer_custom_gcode) + ": " + change.gcode.lineSequence().first()
}

/**
 * Nova ação antes da camada [layer] (a partir de 1), de topo [z]: pausa, troca de filamento
 * (se o projeto permitir) ou G-code personalizado.
 */
@Composable
fun LayerChangeDialog(state: SlicerUiState, viewModel: SlicerViewModel, layer: Int, z: Float, onDismiss: () -> Unit) {
    val actions = listOfNotNull(
        LayerChange.Action.PAUSE,
        LayerChange.Action.FILAMENT_CHANGE.takeIf { state.filamentChangeAvailable },
        LayerChange.Action.CUSTOM,
    )
    var action by remember { mutableStateOf(LayerChange.Action.PAUSE) }
    var filament by remember { mutableStateOf(if (state.filaments.size > 1) 1 else 0) }
    var gcode by remember { mutableStateOf("") }
    val labels = mapOf(
        LayerChange.Action.PAUSE to stringResource(R.string.layer_pause),
        LayerChange.Action.FILAMENT_CHANGE to stringResource(R.string.layer_filament_change),
        LayerChange.Action.CUSTOM to stringResource(R.string.layer_custom_gcode),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layer_change_title, layer, z)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectField(
                    action.name, actions.map { it.name }, { action = LayerChange.Action.valueOf(it) },
                    Modifier.fillMaxWidth(), label = stringResource(R.string.layer_action), labels = actions.map { labels.getValue(it) },
                )
                when (action) {
                    LayerChange.Action.PAUSE -> Text(
                        stringResource(R.string.layer_pause_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LayerChange.Action.FILAMENT_CHANGE -> SelectField(
                        filament.toString(), state.filaments.indices.map { it.toString() }, { filament = it.toInt() },
                        Modifier.fillMaxWidth(), label = stringResource(R.string.filament),
                        labels = state.filaments.mapIndexed { i, name -> "${i + 1} · $name" },
                    )
                    LayerChange.Action.CUSTOM -> FormField(gcode, { gcode = it }, stringResource(R.string.layer_custom_gcode), monospace = true)
                }
                if (!state.filamentChangeAvailable && state.filaments.size > 1) {
                    Text(
                        stringResource(R.string.layer_filament_change_unavailable), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.addLayerChange(LayerChange(z, action, filament, if (action == LayerChange.Action.CUSTOM) gcode else ""))
                },
                enabled = action != LayerChange.Action.CUSTOM || gcode.isNotBlank(),
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
