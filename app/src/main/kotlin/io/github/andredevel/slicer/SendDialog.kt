package io.github.andredevel.slicer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.send.Kind
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.SendException
import io.github.andredevel.slicer.send.QIDI_EXTERNAL
import io.github.andredevel.slicer.send.Slot
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SelectField
import io.github.andredevel.slicer.ui.components.SelectableCard
import io.github.andredevel.slicer.ui.components.Tag
import io.github.andredevel.slicer.ui.components.Tone

/**
 * Enviar o G-code fatiado para uma impressora salva; "Enviar e imprimir" pede confirmação antes
 * de iniciar. Sem impressoras, leva ao cadastro ([onAddPrinter]).
 */
@Composable
fun SendDialog(state: SlicerUiState, viewModel: SlicerViewModel, onAddPrinter: () -> Unit, onDismiss: () -> Unit) {
    val working = state.send is SendState.Working
    // Só vem escolhida a impressora cadastrada com o perfil do fatiamento: outra receberia um
    // G-code feito para outra máquina.
    val preset = viewModel.printerPreset()
    var selectedId by remember { mutableStateOf(state.printers.find { it.preset == preset }?.id) }
    // A pré-escolhida pode estar abaixo da parte visível da lista: rola até ela ao abrir.
    val preselected = remember { BringIntoViewRequester() }
    val preselectedId = remember { selectedId }
    LaunchedEffect(Unit) { if (preselectedId != null) preselected.bringIntoView() }
    var confirmPrint by remember { mutableStateOf(false) }
    val close = {
        viewModel.cancelSend()
        viewModel.clearSendState()
        onDismiss()
    }
    val selected = state.printers.find { it.id == selectedId }
    WatchPrinterStatus(state.printers, viewModel)
    // Imprimindo ou pausada: só dá para enviar.
    val selectedBusy = selected?.let { state.printerStatus[it.id] }?.busy == true
    if (confirmPrint && selected != null) {
        val recolor = { colors: Map<Int, String> ->
            viewModel.recolorAndSlice(colors)
            onDismiss()
        }
        ConfirmPrint(selected, state, viewModel, { confirmPrint = false }, recolor) { mapping ->
            confirmPrint = false
            viewModel.sendGcode(selected, start = true, mapping = mapping)
        }
        return
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 720
    Dialog(onDismissRequest = { if (!working) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = if (wide) 760.dp else 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.send_title), style = MaterialTheme.typography.titleLarge)
                        Text(sendSubtitle(state, viewModel, preset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppIconButton(R.drawable.ic_close, stringResource(R.string.close), close, enabled = !working)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val printers: @Composable ColumnScope.() -> Unit = {
                    SectionLabel(stringResource(R.string.saved_printers))
                    for (host in state.printers) {
                        SelectableCard(
                            selected = host.id == selectedId,
                            onClick = {
                                if (selectedId != host.id) viewModel.clearSendState()
                                selectedId = host.id
                            },
                            modifier = if (host.id == preselectedId) Modifier.bringIntoViewRequester(preselected) else Modifier,
                            enabled = !working, accent = true, radio = true,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                PrinterLabel(host)
                                state.printerStatus[host.id]?.let { PrinterStatusDot(it) }
                                if (host.preset != null && host.preset == preset) {
                                    Tag(stringResource(R.string.slice_profile), container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                    AppButton(stringResource(R.string.add_printer), onAddPrinter, style = ButtonStyle.TEXT, icon = R.drawable.ic_add, enabled = !working)
                }
                val details: @Composable ColumnScope.() -> Unit = {
                    if (selected?.preset != null && preset != null && selected.preset != preset) {
                        Banner(Tone.WARNING, stringResource(R.string.send_preset_mismatch, selected.preset, preset), Modifier.fillMaxWidth(), title = stringResource(R.string.different_profile))
                    }
                    if (selected != null && selectedBusy) {
                        Banner(Tone.WARNING, stringResource(R.string.send_printer_busy, selected.name), Modifier.fillMaxWidth())
                    }
                    if (selected?.kind == Kind.BAMBU_LAN) {
                        Banner(Tone.INFO, stringResource(R.string.bambu_send_note), Modifier.fillMaxWidth())
                    }
                    SendStatus(state.send)
                }
                if (wide) {
                    Row(Modifier.heightIn(max = 420.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = printers)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = details)
                    }
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        printers()
                        details()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (wide) {
                        Text(stringResource(R.string.local_network_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 12.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (working) AppButton(stringResource(R.string.cancel_send), viewModel::cancelSend, style = ButtonStyle.TEXT)
                    AppButton(
                        stringResource(R.string.send_only), { selected?.let { viewModel.sendGcode(it) } },
                        style = ButtonStyle.OUTLINED, icon = R.drawable.ic_upload_file, enabled = selected != null && !working,
                    )
                    if (selected == null || viewModel.canStart(selected)) {
                        AppButton(
                            stringResource(R.string.send_and_print), { confirmPrint = true },
                            icon = R.drawable.ic_play_circle, enabled = selected != null && !working && !selectedBusy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sendSubtitle(state: SlicerUiState, viewModel: SlicerViewModel, preset: String?): String {
    val stats = (state.slice as? SliceState.Done)?.stats
    return listOfNotNull(
        viewModel.gcodeName(),
        stats?.let { formatDuration(it.printTimeSeconds) },
        stats?.let { String.format(java.util.Locale.getDefault(), "%.1f g", it.filamentGrams) },
        preset?.let { stringResource(R.string.sliced_for, it) },
    ).joinToString(" · ")
}

/** Leitura de [SlicerViewModel.printerSlots] na confirmação. */
private sealed interface SlotsLoad {
    data object Loading : SlotsLoad
    data class Loaded(val slots: List<Slot>) : SlotsLoad
    data class Failed(val message: String) : SlotsLoad
}

/**
 * Confirmação de "Enviar e imprimir": só libera depois de conferir mesa e filamento. Numa
 * impressora que mapeia filamentos ([SlicerViewModel.printerSlots]), mostra a posição de cada
 * filamento usado, já escolhida pela cor, e [onConfirm] recebe o mapeamento; senão, null. Se a
 * cor de um filamento difere da posição escolhida, [onRecolor] recebe as cores das posições
 * (filamento -> "#RRGGBB") para fatiar de novo com elas.
 */
@Composable
private fun ConfirmPrint(
    host: PrinterHost, state: SlicerUiState, viewModel: SlicerViewModel, onDismiss: () -> Unit,
    onRecolor: (Map<Int, String>) -> Unit, onConfirm: (List<Slot?>?) -> Unit,
) {
    var bedClear by remember { mutableStateOf(false) }
    var filamentLoaded by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<SlotsLoad>(SlotsLoad.Loading) }
    val context = LocalContext.current
    LaunchedEffect(host.id, attempt) {
        load = SlotsLoad.Loading
        load = try {
            SlotsLoad.Loaded(viewModel.printerSlots(host))
        } catch (e: SendException) {
            SlotsLoad.Failed(context.getString(e.text, *e.args))
        }
    }
    val colors = state.scene?.filamentColors.orEmpty()
    // Filamentos com gasto no fatiamento; um G-code sem esse dado usa o primeiro.
    val used = (state.slice as? SliceState.Done)?.stats?.filaments?.filter { it.meters > 0 || it.grams > 0 }?.map { it.index }
        ?.takeIf { it.isNotEmpty() } ?: listOf(0)
    val slots = (load as? SlotsLoad.Loaded)?.slots.orEmpty()
    val mapping = remember(slots) {
        mutableStateListOf<Int>().apply { if (slots.isNotEmpty()) addAll(defaultMapping(maxOf(state.filaments.size, used.max() + 1), used, colors, state.filamentTypes, slots)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_play_circle), null) },
        title = { Text(stringResource(R.string.print_confirm_title_named, host.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val l = load) {
                    SlotsLoad.Loading -> Text(stringResource(R.string.mapping_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is SlotsLoad.Failed -> {
                        Banner(Tone.ERROR, stringResource(R.string.mapping_failed, l.message), Modifier.fillMaxWidth())
                        AppButton(stringResource(R.string.action_retry), { attempt++ }, style = ButtonStyle.TEXT)
                    }
                    is SlotsLoad.Loaded -> if (mapping.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.filament_mapping))
                        val fixed = l.slots.any { it.nozzle != null }
                        for (filament in used) {
                            MappingRow(filament, colors.getOrNull(filament), state.filaments.getOrNull(filament), l.slots, mapping[filament], enabled = !fixed) { mapping[filament] = it }
                        }
                        if (fixed) {
                            Text(stringResource(R.string.mapping_fixed_nozzles), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        for (filament in used.filter { it !in mapping.indices || mapping[it] < 0 }) {
                            Banner(Tone.ERROR, stringResource(R.string.mapping_no_slot, filament + 1), Modifier.fillMaxWidth())
                        }
                        for (slot in used.mapNotNull { l.slots.getOrNull(mapping[it]) }.distinct().filter { !it.loaded }) {
                            Banner(Tone.WARNING, stringResource(R.string.mapping_slot_empty, slotName(slot)), Modifier.fillMaxWidth())
                        }
                        for (filament in used) {
                            val slot = l.slots.getOrNull(mapping[filament])?.takeIf { it.loaded } ?: continue
                            val type = state.filamentTypes.getOrNull(filament)
                            if (type != null && slot.type != null && !sameType(type, slot.type)) {
                                Banner(
                                    Tone.WARNING, stringResource(R.string.mapping_type_differs, filament + 1, type, slotName(slot), slot.type),
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        // Cor do projeto diferente da carregada: a vista prévia e a purga não batem com a peça.
                        val slotColors = used.mapNotNull { f ->
                            l.slots.getOrNull(mapping[f])?.takeIf { it.loaded }?.color?.let { f to it.uppercase() }
                        }.filter { (f, hex) -> colorDistance(colors.getOrNull(f), hex) > COLOR_SAME }.toMap()
                        if (slotColors.isNotEmpty()) {
                            Text(stringResource(R.string.mapping_colors_differ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppButton(stringResource(R.string.mapping_use_printer_colors), { onRecolor(slotColors) }, style = ButtonStyle.TEXT)
                        }
                    }
                }
                CheckRow(stringResource(R.string.check_bed_clear), bedClear) { bedClear = it }
                val filament = state.presets[PresetKind.FILAMENT]?.selected
                CheckRow(
                    when {
                        mapping.isNotEmpty() -> stringResource(R.string.check_mapping)
                        filament != null -> stringResource(R.string.check_filament_named, filament)
                        else -> stringResource(R.string.check_filament)
                    },
                    filamentLoaded,
                ) { filamentLoaded = it }
                Text(stringResource(R.string.print_heats_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(mapping.takeIf { it.isNotEmpty() }?.mapIndexed { i, slot -> if (i in used) slots[slot] else null }) },
                enabled = bedClear && filamentLoaded && load is SlotsLoad.Loaded && used.all { mapping.isEmpty() || mapping[it] >= 0 },
            ) { Text(stringResource(R.string.print_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } },
    )
}

/**
 * Filamento do projeto e, embaixo, a posição da impressora escolhida para ele: lado a lado, no
 * celular os dois nomes ficavam cortados.
 */
@Composable
private fun MappingRow(filament: Int, color: FloatArray?, name: String?, slots: List<Slot>, slot: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    FilamentLabel(filament, color, name, Modifier.padding(top = 4.dp))
    Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(12.dp)
                .background(slots.getOrNull(slot)?.color?.let { Color(android.graphics.Color.parseColor(it)) } ?: MaterialTheme.colorScheme.outline, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        val labels = slots.map { s ->
            listOf(slotName(s), if (s.loaded) s.type else stringResource(R.string.slot_empty)).filterNotNull().joinToString(" · ")
        }
        SelectField(slot.toString(), slots.indices.map { it.toString() }, { onSelect(it.toInt()) }, Modifier.weight(1f), labels = labels, enabled = enabled)
    }
}

/** "Cabeça 2" na U1, "Slot 1A" (caixa e posição) no CFS da Creality, "Box 1, posição 3" na QIDI. */
@Composable
private fun slotName(slot: Slot): String = when {
    slot.cfsBox != null -> stringResource(R.string.cfs_slot, "${slot.cfsBox}${'A' + slot.cfsSlot}")
    slot.qidiSlot == QIDI_EXTERNAL -> stringResource(R.string.external_spool)
    slot.qidiSlot != null -> stringResource(R.string.qidi_box_slot, slot.qidiSlot / 4 + 1, slot.qidiSlot % 4 + 1)
    else -> stringResource(R.string.toolhead_n, slot.index + 1)
}

/**
 * Posição de cada filamento do projeto (0 até [count]): cada um de [used] vai para a posição
 * carregada ainda livre do mesmo material ([types]) e, entre elas, de cor mais próxima; sem uma
 * do mesmo material, para a de cor mais próxima. Os outros, e os que sobram sem posição
 * carregada, vão para a de mesmo número. Com bicos fixos ([Slot.nozzle]), é a posição do bico do
 * filamento, ou -1 se nenhuma o alimenta.
 */
private fun defaultMapping(count: Int, used: List<Int>, colors: List<FloatArray?>, types: List<String>, slots: List<Slot>): List<Int> {
    if (slots.any { it.nozzle != null }) return List(count) { i -> slots.indexOfFirst { it.nozzle == i + 1 } }
    val taken = mutableSetOf<Int>()
    return List(count) { i ->
        val fallback = i % slots.size
        if (i !in used) return@List fallback
        val color = colors.getOrNull(i)
        val type = types.getOrNull(i)
        val free = slots.indices.filter { slots[it].loaded && it !in taken }
        val sameMaterial = free.filter { type != null && slots[it].type?.let { slotType -> sameType(type, slotType) } == true }
        val best = sameMaterial.ifEmpty { free }.minByOrNull { colorDistance(color, slots[it].color) }
        (best ?: fallback).also { taken += it }
    }
}

/** Mesmo material: a impressora e o perfil escrevem o tipo com caixa e espaços diferentes. */
private fun sameType(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

/** [colorDistance] abaixo disto: a mesma cor (arredondamento de 8 bits por canal). */
private const val COLOR_SAME = 0.0005f

/** Distância RGB ao quadrado; sem uma das cores, maior que qualquer distância real. */
private fun colorDistance(rgba: FloatArray?, hex: String?): Float {
    if (rgba == null || hex == null) return 4f
    val v = hex.removePrefix("#").toInt(16)
    val rgb = floatArrayOf(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f)
    return (0..2).sumOf { ((rgba[it] - rgb[it]) * (rgba[it] - rgb[it])).toDouble() }.toFloat()
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}
