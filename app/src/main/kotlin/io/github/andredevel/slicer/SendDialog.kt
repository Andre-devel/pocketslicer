package io.github.andredevel.slicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.Kind
import io.github.andredevel.slicer.send.PrinterHost

/**
 * Enviar o G-code fatiado para uma impressora salva, ou cadastrar uma; "Enviar e imprimir" pede
 * confirmação antes de iniciar a impressão.
 */
@Composable
fun SendDialog(state: SlicerUiState, viewModel: SlicerViewModel, onDismiss: () -> Unit) {
    val working = state.send is SendState.Working
    // Impressora em edição; começa no cadastro quando não há nenhuma salva.
    var editing by remember { mutableStateOf(if (state.printers.isEmpty()) newHost(viewModel) else null) }
    // Só vem escolhida a impressora cadastrada com o perfil do fatiamento: outra receberia um
    // G-code feito para outra máquina.
    val preset = viewModel.printerPreset()
    var selectedId by remember { mutableStateOf(state.printers.find { it.preset == preset }?.id) }
    var confirmPrint by remember { mutableStateOf(false) }
    val close = {
        viewModel.cancelSend()
        viewModel.clearSendState()
        onDismiss()
    }
    // O resultado do último envio ou teste não vale para outra impressora.
    val edit = { host: PrinterHost ->
        viewModel.clearSendState()
        editing = host
    }
    val edited = editing
    if (edited != null) {
        PrinterForm(edited, state.send, isNew = state.printers.none { it.id == edited.id }, viewModel,
            onChange = { editing = it },
            onSave = {
                viewModel.savePrinter(it)
                viewModel.clearSendState()
                selectedId = it.id
                editing = null
            },
            onDelete = {
                viewModel.deletePrinter(edited.id)
                viewModel.clearSendState()
                if (selectedId == edited.id) selectedId = null
                editing = null
            },
            onBack = {
                viewModel.cancelSend()
                viewModel.clearSendState()
                if (state.printers.isEmpty()) close() else editing = null
            },
        )
        return
    }

    val selected = state.printers.find { it.id == selectedId }
    if (confirmPrint && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmPrint = false },
            title = { Text(stringResource(R.string.print_confirm_title)) },
            text = { Text(stringResource(R.string.print_confirm, selected.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrint = false
                    viewModel.sendGcode(selected, start = true)
                }) { Text(stringResource(R.string.print_start)) }
            },
            dismissButton = { TextButton(onClick = { confirmPrint = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!working) close() },
        title = { Text(stringResource(R.string.send_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.send_file, viewModel.gcodeName()), style = MaterialTheme.typography.bodyMedium)
                for (host in state.printers) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = host.id == selectedId, enabled = !working) {
                            if (selectedId != host.id) viewModel.clearSendState()
                            selectedId = host.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = host.id == selectedId, onClick = null, enabled = !working)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(host.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${stringResource(host.kind.label)} · ${host.address}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { edit(host) }, enabled = !working) { Text(stringResource(R.string.edit)) }
                    }
                }
                TextButton(onClick = { edit(newHost(viewModel)) }, enabled = !working) { Text(stringResource(R.string.add_printer)) }
                if (selected?.preset != null && preset != null && selected.preset != preset) {
                    Text(stringResource(R.string.send_preset_mismatch, selected.preset, preset), color = MaterialTheme.colorScheme.error)
                }
                SendStatus(state.send)
            }
        },
        confirmButton = {
            Row {
                if (selected == null || viewModel.canStart(selected)) {
                    TextButton(onClick = { confirmPrint = true }, enabled = selected != null && !working) {
                        Text(stringResource(R.string.send_and_print))
                    }
                }
                TextButton(onClick = { selected?.let { viewModel.sendGcode(it) } }, enabled = selected != null && !working) {
                    Text(stringResource(R.string.send))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = if (working) viewModel::cancelSend else close) {
                Text(stringResource(if (working) R.string.cancel else R.string.close))
            }
        },
    )
}

@Composable
private fun PrinterForm(
    host: PrinterHost,
    send: SendState,
    isNew: Boolean,
    viewModel: SlicerViewModel,
    onChange: (PrinterHost) -> Unit,
    onSave: (PrinterHost) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val working = send is SendState.Working
    val bambu = host.kind == Kind.BAMBU_LAN
    // A Bambu sempre pede o código de acesso; a API key do Moonraker é opcional.
    val valid = host.name.isNotBlank() && host.address.isNotBlank() && (!bambu || !host.secret.isNullOrBlank())
    // Espaços nas pontas (teclado, colar) quebrariam o endereço.
    val withName = { host.copy(name = host.name.trim(), address = host.address.trim()) }
    AlertDialog(
        onDismissRequest = { if (!working) onBack() },
        title = { Text(stringResource(if (isNew) R.string.add_printer else R.string.edit_printer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) {
                    Text(stringResource(R.string.printer_kind, ""), style = MaterialTheme.typography.bodyMedium)
                    for (kind in Kind.entries) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = host.kind == kind, enabled = !working) { onChange(host.copy(kind = kind)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = host.kind == kind, onClick = null, enabled = !working)
                            Text(stringResource(kind.label), Modifier.padding(start = 8.dp))
                        }
                    }
                } else {
                    Text(stringResource(R.string.printer_kind, stringResource(host.kind.label)), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = host.name,
                    onValueChange = { onChange(host.copy(name = it)) },
                    label = { Text(stringResource(R.string.printer_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host.address,
                    onValueChange = { onChange(host.copy(address = it)) },
                    label = { Text(stringResource(R.string.printer_address)) },
                    placeholder = { Text("192.168.0.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                run {
                    OutlinedTextField(
                        value = host.secret.orEmpty(),
                        onValueChange = { onChange(host.copy(secret = it.ifEmpty { null })) },
                        label = { Text(stringResource(if (bambu) R.string.printer_access_code else R.string.printer_api_key)) },
                        supportingText = if (bambu) ({ Text(stringResource(R.string.printer_access_code_hint)) }) else null,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.testPrinter(withName()) }, enabled = valid && !working) {
                        Text(stringResource(R.string.test_connection))
                    }
                    if (!isNew) {
                        TextButton(onClick = onDelete, enabled = !working) { Text(stringResource(R.string.delete)) }
                    }
                }
                SendStatus(send)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(withName()) }, enabled = valid && !working) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SendStatus(send: SendState) {
    when (send) {
        SendState.Idle -> {}
        is SendState.Working -> {
            val progress = send.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                if (progress == null) stringResource(send.text) else stringResource(send.text) + " ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is SendState.Done -> Text(stringResource(send.text, send.printer), color = MaterialTheme.colorScheme.primary)
        is SendState.Failed -> Text(send.message, color = MaterialTheme.colorScheme.error)
    }
}

private fun newHost(viewModel: SlicerViewModel) =
    PrinterHost(viewModel.newPrinterId(), name = viewModel.printerPreset().orEmpty(), kind = viewModel.printerKind(), address = "", preset = viewModel.printerPreset())
