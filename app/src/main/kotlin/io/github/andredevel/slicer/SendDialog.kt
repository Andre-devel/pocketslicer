package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.send.Kind
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.SectionLabel
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
        ConfirmPrint(selected.name, state.presets[PresetKind.FILAMENT]?.selected, { confirmPrint = false }) {
            confirmPrint = false
            viewModel.sendGcode(selected, start = true)
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

/** Confirmação de "Enviar e imprimir": só libera depois de conferir mesa e filamento. */
@Composable
private fun ConfirmPrint(printer: String, filament: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var bedClear by remember { mutableStateOf(false) }
    var filamentLoaded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_play_circle), null) },
        title = { Text(stringResource(R.string.print_confirm_title_named, printer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CheckRow(stringResource(R.string.check_bed_clear), bedClear) { bedClear = it }
                CheckRow(
                    if (filament != null) stringResource(R.string.check_filament_named, filament) else stringResource(R.string.check_filament),
                    filamentLoaded,
                ) { filamentLoaded = it }
                Text(stringResource(R.string.print_heats_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = bedClear && filamentLoaded) { Text(stringResource(R.string.print_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } },
    )
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
