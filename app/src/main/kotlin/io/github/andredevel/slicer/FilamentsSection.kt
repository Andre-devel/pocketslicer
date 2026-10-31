package io.github.andredevel.slicer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.NumberField
import io.github.andredevel.slicer.ui.components.PillTabs
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SelectField

/**
 * Filamentos do projeto, como a barra lateral do Orca: cor e preset de cada um, acrescentar e
 * excluir. Os parâmetros em edição são os do primeiro (o selecionado em [PresetKind.FILAMENT]).
 */
@Composable
fun FilamentsSection(state: SlicerUiState, viewModel: SlicerViewModel, enabled: Boolean, openParameters: (PresetKind) -> Unit) {
    var coloring by remember { mutableStateOf<Int?>(null) }
    var removing by remember { mutableStateOf<Int?>(null) }
    var flushing by remember { mutableStateOf(false) }
    // Recolhida, a seção mostra só as cores; tocar no título ou nelas abre a lista.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count = state.filaments.size
    val toggle = stringResource(if (expanded) R.string.collapse else R.string.expand)
    SectionLabel(
        stringResource(R.string.filaments) + " · $count",
        Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = toggle) { expanded = !expanded },
    ) {
        AppIconButton(
            R.drawable.ic_tune, stringResource(R.string.edit_filament_parameters), { openParameters(PresetKind.FILAMENT) },
            enabled = enabled,
        )
        AppIconButton(
            R.drawable.ic_add, stringResource(R.string.add_filament), { expanded = true; viewModel.addFilament() },
            enabled = enabled && count < state.filamentLimits.last,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right), toggle,
            Modifier.rotate(if (expanded) 270f else 90f), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!expanded) {
        FlowRow(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { expanded = true },
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until count) ColorSwatch(state.scene?.filamentColors?.getOrNull(i), "${i + 1}", Modifier.size(28.dp))
        }
        return
    }
    val options = state.presets[PresetKind.FILAMENT]?.options.orEmpty()
    state.filaments.forEachIndexed { i, preset ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorSwatch(
                state.scene?.filamentColors?.getOrNull(i), "${i + 1}",
                Modifier.clickable(enabled = enabled) { coloring = i },
            )
            SelectField(preset, options, { viewModel.setFilamentPreset(i, it) }, Modifier.weight(1f), enabled = enabled)
            if (count > state.filamentLimits.first) {
                AppIconButton(R.drawable.ic_remove, stringResource(R.string.remove_filament, i + 1), { removing = i }, enabled = enabled)
            }
        }
    }

    if (count > 1) {
        AppButton(
            stringResource(R.string.flush_volumes), { flushing = true }, Modifier.fillMaxWidth(),
            style = ButtonStyle.OUTLINED, enabled = enabled,
        )
    }

    if (flushing) FlushDialog(state, viewModel) { flushing = false }
    coloring?.let { i ->
        ColorDialog(
            current = state.scene?.filamentColors?.getOrNull(i)?.let(::hexColor),
            title = stringResource(R.string.filament_n, i + 1),
            onDismiss = { coloring = null },
            onPick = { coloring = null; viewModel.setFilamentColor(i, it) },
        )
    }
    removing?.let { i ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_filament, i + 1)) },
            text = { Text(stringResource(R.string.remove_filament_detail, i + 1)) },
            confirmButton = {
                TextButton(onClick = { removing = null; viewModel.removeFilament(i) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Volumes de purga de cada troca (linha: de qual filamento; coluna: para qual), como a janela
 * do Orca. Cada valor vale ao sair do campo; "Recalcular" refaz a matriz pelas cores.
 */
@Composable
private fun FlushDialog(state: SlicerUiState, viewModel: SlicerViewModel, onDismiss: () -> Unit) {
    val n = state.filaments.size
    val nozzles = if (n == 0) 0 else state.flushVolumes.size / (n * n)
    var nozzle by remember { mutableStateOf(0) }
    val focus = LocalFocusManager.current
    // Fecha depois de tirar o foco, para o campo em edição gravar o valor.
    val close = { focus.clearFocus(); onDismiss() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.flush_volumes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.flush_volumes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nozzles > 1) {
                    PillTabs((1..nozzles).map { stringResource(R.string.extruder_n, it) }, nozzle, { nozzle = it })
                }
                Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(CELL_WIDTH / 2))
                        for (to in 0 until n) {
                            Box(Modifier.width(CELL_WIDTH), contentAlignment = Alignment.Center) {
                                ColorSwatch(state.scene?.filamentColors?.getOrNull(to), "${to + 1}", Modifier.size(28.dp))
                            }
                        }
                    }
                    for (from in 0 until n) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(CELL_WIDTH / 2)) {
                                ColorSwatch(state.scene?.filamentColors?.getOrNull(from), "${from + 1}", Modifier.size(28.dp))
                            }
                            for (to in 0 until n) {
                                val value = state.flushVolumes.getOrNull(nozzle * n * n + from * n + to) ?: 0.0
                                NumberField(
                                    value.toFloat(), { viewModel.setFlushVolume(nozzle, from, to, it.toDouble()) },
                                    Modifier.width(CELL_WIDTH), decimals = 0, min = 0f, enabled = from != to,
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.flush_multiplier), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    NumberField(
                        (state.flushMultipliers.getOrNull(nozzle) ?: 1.0).toFloat(),
                        { viewModel.setFlushMultiplier(nozzle, it.toDouble()) },
                        Modifier.width(CELL_WIDTH), min = 0.01f,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = { focus.clearFocus(); viewModel.calcFlushVolumes() }) { Text(stringResource(R.string.recalculate)) }
        },
    )
}

private val CELL_WIDTH = 72.dp

/** Filamento do objeto selecionado; só com mais de um no projeto. */
@Composable
fun ObjectFilamentField(state: SlicerUiState, viewModel: SlicerViewModel, objectIndex: Int, enabled: Boolean) {
    val mesh = state.scene?.meshes?.getOrNull(objectIndex) ?: return
    if (state.filaments.size < 2) return
    SelectField(
        mesh.filament.toString(), state.filaments.indices.map { it.toString() },
        { viewModel.setObjectFilament(objectIndex, it.toInt()) },
        Modifier.fillMaxWidth(), label = stringResource(R.string.filament),
        labels = state.filaments.mapIndexed { i, name -> "${i + 1} · $name" }, enabled = enabled,
    )
}

/** Círculo na cor do filamento com o número dele. */
@Composable
internal fun ColorSwatch(color: FloatArray?, text: String, modifier: Modifier = Modifier) {
    val fill = color?.let { Color(it[0], it[1], it[2]) } ?: MaterialTheme.colorScheme.outline
    Box(
        modifier.size(36.dp).background(fill, CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (luminance(fill) > 0.5f) Color.Black else Color.White)
    }
}

/** Paleta de cores comuns de filamento e o código `#RRGGBB`. */
@Composable
private fun ColorDialog(current: String?, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var hex by remember { mutableStateOf(current.orEmpty()) }
    val valid = HEX.matches(hex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (color in PALETTE) {
                        val rgb = parseHex(color)
                        Box(
                            Modifier.size(40.dp)
                                .background(Color(rgb[0], rgb[1], rgb[2]), CircleShape)
                                .border(
                                    if (color.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                                    if (color.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                                .clickable { hex = color },
                        )
                    }
                }
                FormField(hex, { hex = it.trim().uppercase() }, stringResource(R.string.color_hex), monospace = true, isError = hex.isNotEmpty() && !valid)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hex.uppercase()) }, enabled = valid) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val HEX = Regex("#[0-9A-Fa-f]{6}")

/** Cores de filamento comuns: as básicas e a paleta de filamento novo do Orca. */
private val PALETTE = listOf(
    "#FFFFFF", "#000000", "#808080", "#C0C0C0", "#ED1C24", "#F26722", "#FFEB31", "#00AE42",
    "#2EBDEF", "#115877", "#7841CE", "#ED1E79", "#7C4B00", "#F4E2C1", "#00C1AE", "#A4C41E",
)

private fun parseHex(color: String): FloatArray {
    val v = color.removePrefix("#").toLong(16)
    return floatArrayOf(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)
}

private fun hexColor(rgba: FloatArray) =
    "#%02X%02X%02X".format((rgba[0] * 255).toInt(), (rgba[1] * 255).toInt(), (rgba[2] * 255).toInt())

private fun luminance(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
