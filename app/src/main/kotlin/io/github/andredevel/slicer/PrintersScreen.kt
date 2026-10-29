package io.github.andredevel.slicer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.FoundPrinter
import io.github.andredevel.slicer.send.Kind
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterScanner
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.AppIconButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.ColumnDivider
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.InfoCard
import io.github.andredevel.slicer.ui.components.ScreenHeader
import io.github.andredevel.slicer.ui.components.Segment
import io.github.andredevel.slicer.ui.components.SegmentedTabs
import io.github.andredevel.slicer.ui.components.SelectableCard
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.components.color
import io.github.andredevel.slicer.ui.theme.MonoStyles
import kotlinx.coroutines.launch

/**
 * Impressoras salvas para o envio do G-code: lista, cadastro/edição e busca na rede. Na tela
 * larga, as três colunas lado a lado; na estreita, a lista e o formulário por cima dela.
 */
@Composable
fun PrintersScreen(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier) {
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_DP
    // Impressora em edição (salva ou nova); na tela larga começa pela primeira da lista.
    var editing by remember { mutableStateOf(if (wide) state.printers.firstOrNull() ?: newHost(viewModel) else null) }
    val edit = { host: PrinterHost? ->
        // O resultado do último teste não vale para outra impressora.
        viewModel.cancelSend()
        viewModel.clearSendState()
        editing = host
    }
    val pick = { found: FoundPrinter ->
        val host = newHost(viewModel)
        edit(host.copy(name = found.name, kind = found.kind ?: host.kind, address = found.address))
    }
    val edited = editing
    val form = @Composable { host: PrinterHost, formModifier: Modifier ->
        PrinterForm(
            host, state, viewModel, formModifier,
            onChange = { editing = it },
            onSave = {
                viewModel.savePrinter(it)
                viewModel.clearSendState()
                editing = if (wide) it else null
            },
            onDelete = {
                viewModel.deletePrinter(host.id)
                edit(if (wide) state.printers.firstOrNull { it.id != host.id } ?: newHost(viewModel) else null)
            },
            onClose = if (wide) null else ({ edit(null) }),
        )
    }

    if (!wide && edited != null) {
        form(edited, modifier)
        return
    }
    Row(modifier) {
        Column(Modifier.then(if (wide) Modifier.width(300.dp) else Modifier.weight(1f)).fillMaxHeight()) {
            ScreenHeader(stringResource(R.string.printers)) {
                AppButton(stringResource(R.string.add), { edit(newHost(viewModel)) }, style = ButtonStyle.TONAL, icon = R.drawable.ic_add)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.printers.isEmpty()) {
                    Text(
                        stringResource(R.string.no_printers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                for (host in state.printers) {
                    SelectableCard(selected = host.id == edited?.id, onClick = { edit(host) }) {
                        Icon(painterResource(R.drawable.ic_print), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrinterLabel(host, Modifier.weight(1f))
                    }
                }
                if (!wide) NetworkScan(state.printers, pick, Modifier.padding(top = 8.dp))
            }
        }
        if (wide) {
            ColumnDivider()
            form(edited ?: newHost(viewModel), Modifier.weight(1f).fillMaxHeight())
            ColumnDivider()
            Column(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                NetworkScan(state.printers, pick, Modifier.verticalScroll(rememberScrollState()))
            }
        }
    }
}

/** Nome, endereço e tipo de uma impressora salva. */
@Composable
fun PrinterLabel(host: PrinterHost, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(host.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${host.address} · ${stringResource(host.kind.shortLabel)}",
            style = MonoStyles.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Cadastro ou edição de [host]; [onClose] (tela estreita) volta à lista. */
@Composable
private fun PrinterForm(
    host: PrinterHost,
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    modifier: Modifier,
    onChange: (PrinterHost) -> Unit,
    onSave: (PrinterHost) -> Unit,
    onDelete: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val send = state.send
    val working = send is SendState.Working
    val isNew = state.printers.none { it.id == host.id }
    val bambu = host.kind == Kind.BAMBU_LAN
    val (address, port) = splitAddress(host.address)
    val addressCheck = checkAddress(address.trim())
    val portValid = port.isEmpty() || port.toIntOrNull() in 1..65535
    // A Bambu sempre pede o código de acesso; a API key do Moonraker é opcional.
    val valid = host.name.isNotBlank() && addressCheck == AddressCheck.OK && portValid && (!bambu || !host.secret.isNullOrBlank())
    // Espaços nas pontas (teclado, colar) quebrariam o endereço.
    val trimmed = { host.copy(name = host.name.trim(), address = host.address.trim()) }
    var showSecret by remember(host.id) { mutableStateOf(false) }
    val portFocus = remember { FocusRequester() }

    Column(modifier) {
        ScreenHeader(stringResource(if (isNew) R.string.new_printer else R.string.edit_printer)) {
            if (onClose != null) AppIconButton(R.drawable.ic_close, stringResource(R.string.close), onClose)
            if (!isNew) {
                AppButton(
                    stringResource(R.string.delete), onDelete, style = ButtonStyle.TEXT, icon = R.drawable.ic_delete,
                    enabled = !working, color = MaterialTheme.colorScheme.error,
                )
            }
            AppButton(stringResource(R.string.save), { onSave(trimmed()) }, enabled = valid && !working)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedTabs(
                Kind.entries.map { Segment(stringResource(it.label), enabled = isNew && !working) },
                host.kind.ordinal,
                { onChange(host.copy(kind = Kind.entries[it])) },
                Modifier.fillMaxWidth(),
            )
            Text(stringResource(host.kind.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FormField(host.name, { onChange(host.copy(name = it)) }, stringResource(R.string.printer_name), enabled = !working)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    address,
                    { text ->
                        // "ip:porta" digitado ou colado: a porta vai para o campo dela.
                        val (typedAddress, typedPort) = splitAddress(text)
                        if (typedAddress != text) {
                            onChange(host.copy(address = joinAddress(typedAddress, typedPort.ifEmpty { port })))
                            portFocus.requestFocus()
                        } else {
                            onChange(host.copy(address = joinAddress(text, port)))
                        }
                    },
                    stringResource(R.string.printer_address),
                    Modifier.weight(1f), enabled = !working, placeholder = "192.168.0.50", monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = addressCheck == AddressCheck.INVALID,
                    supportingText = when (addressCheck) {
                        AddressCheck.INVALID -> stringResource(R.string.address_invalid)
                        AddressCheck.INCOMPLETE -> stringResource(R.string.address_incomplete)
                        else -> null
                    },
                )
                FormField(
                    port, { text -> onChange(host.copy(address = joinAddress(address, text.filter(Char::isDigit)))) },
                    stringResource(R.string.printer_port), Modifier.width(120.dp).focusRequester(portFocus), enabled = !working,
                    placeholder = stringResource(R.string.port_default), monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !portValid,
                )
            }
            FormField(
                host.secret.orEmpty(), { onChange(host.copy(secret = it.ifEmpty { null })) },
                stringResource(if (bambu) R.string.printer_access_code else R.string.printer_api_key),
                enabled = !working, monospace = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            painterResource(if (showSecret) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            stringResource(if (showSecret) R.string.hide else R.string.show),
                        )
                    }
                },
            )
            if (bambu) InfoCard(stringResource(R.string.printer_access_code_hint), icon = R.drawable.ic_lightbulb)
            LinkedPreset(host, viewModel.printerPreset(), enabled = !working) { onChange(host.copy(preset = it)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    stringResource(R.string.test_connection), { viewModel.testPrinter(trimmed()) },
                    style = ButtonStyle.OUTLINED, icon = R.drawable.ic_network_check, enabled = valid && !working,
                )
            }
            SendStatus(send)
            if (bambu) InfoCard(stringResource(R.string.bambu_send_note), icon = R.drawable.ic_info)
        }
    }
}

/**
 * [PrinterHost.address] ("ip:porta") em endereço e porta, para os dois campos do formulário; o
 * esquema (`http://`) fica no endereço.
 */
private fun splitAddress(address: String): Pair<String, String> {
    val schemeEnd = address.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    val colon = address.indexOf(':', schemeEnd)
    if (colon < 0) return address to ""
    return address.substring(0, colon) to address.substring(colon + 1).filter(Char::isDigit)
}

private fun joinAddress(address: String, port: String) = if (port.isEmpty()) address else "$address:$port"

private enum class AddressCheck { OK, EMPTY, INCOMPLETE, INVALID }

/** Só números e pontos é um IPv4, conferido enquanto se digita; o resto vale como nome. */
private fun checkAddress(address: String): AddressCheck {
    if (address.isEmpty()) return AddressCheck.EMPTY
    if (!address.all { it.isDigit() || it == '.' }) return AddressCheck.OK
    val parts = address.split('.')
    return when {
        parts.size > 4 || parts.any { it.length > 3 || (it.toIntOrNull() ?: 0) > 255 } -> AddressCheck.INVALID
        parts.dropLast(1).any { it.isEmpty() } -> AddressCheck.INVALID
        parts.size < 4 || parts.last().isEmpty() -> AddressCheck.INCOMPLETE
        else -> AddressCheck.OK
    }
}

/** Perfil de impressora com que a impressora vem escolhida no envio; troca pelo atual. */
@Composable
private fun LinkedPreset(host: PrinterHost, current: String?, enabled: Boolean, onLink: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.linked_preset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(host.preset ?: stringResource(R.string.none), style = MaterialTheme.typography.titleSmall)
        }
        if (current != null && current != host.preset) {
            AppButton(stringResource(R.string.printer_use_current_preset), { onLink(current) }, style = ButtonStyle.OUTLINED, icon = R.drawable.ic_link, enabled = enabled)
        }
    }
}

/** "Procurar na rede" e as impressoras achadas; [onPick] preenche um cadastro novo. */
@Composable
private fun NetworkScan(saved: List<PrinterHost>, onPick: (FoundPrinter) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    // null: ainda não procurou.
    var found by remember { mutableStateOf<List<FoundPrinter>?>(null) }
    var subnet by remember { mutableStateOf<String?>(null) }
    val scan = {
        scanning = true
        subnet = PrinterScanner.subnet(context.applicationContext)
        scope.launch {
            found = PrinterScanner.scan(context.applicationContext)
            scanning = false
        }
    }
    // Procura sozinha ao abrir a tela.
    LaunchedEffect(Unit) { scan() }
    Column(modifier) {
        ScreenHeader(stringResource(R.string.scan_network)) {
            AppIconButton(R.drawable.ic_refresh, stringResource(R.string.scan_network), { scan() }, enabled = !scanning)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                scanning -> {
                    Text(subnet?.let { stringResource(R.string.scan_running_subnet, it) } ?: stringResource(R.string.scan_running), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                found == null -> AppButton(stringResource(R.string.scan_network), { scan() }, style = ButtonStyle.OUTLINED, icon = R.drawable.ic_search)
                found!!.isEmpty() -> Text(stringResource(R.string.scan_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (printer in found.orEmpty()) {
                val kind = printer.kind
                val known = saved.any { it.address.substringBefore(':') == printer.address }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(printer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            printer.address + (kind?.let { " · " + stringResource(it.shortLabel) } ?: ""),
                            style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (kind == null) {
                            Text(stringResource(R.string.scan_unsupported_short), style = MaterialTheme.typography.labelSmall, color = Tone.WARNING.color())
                        }
                    }
                    when {
                        known -> Text(stringResource(R.string.already_saved), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        kind != null -> AppButton(stringResource(R.string.add), { onPick(printer) }, style = ButtonStyle.TONAL)
                    }
                }
            }
        }
    }
}

/** Andamento ou resultado do último envio/teste. */
@Composable
fun SendStatus(send: SendState) {
    when (send) {
        SendState.Idle -> {}
        is SendState.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val progress = send.progress
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_upload), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(send.text), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (progress != null) Text("${(progress * 100).toInt()}%", style = MonoStyles.small)
            }
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        is SendState.Done -> Banner(Tone.SUCCESS, stringResource(send.text, send.printer), Modifier.fillMaxWidth())
        is SendState.Failed -> Banner(Tone.ERROR, send.message, Modifier.fillMaxWidth())
    }
}

fun newHost(viewModel: SlicerViewModel) =
    PrinterHost(viewModel.newPrinterId(), name = viewModel.printerPreset().orEmpty(), kind = viewModel.printerKind(), address = "", preset = viewModel.printerPreset())

private const val WIDE_DP = 840
