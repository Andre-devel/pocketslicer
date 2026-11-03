package io.github.andredevel.slicer

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.ConsoleLine
import io.github.andredevel.slicer.send.PrinterHost
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.send.SendException
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.Tone
import io.github.andredevel.slicer.ui.theme.MonoStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/** Linhas do console lidas de cada vez. */
private const val CONSOLE_LINES = 200
private const val CONSOLE_POLL_MS = 2_000L

/** Sugestões de comando mostradas enquanto se digita. */
private const val SUGGESTIONS = 8

/**
 * Aba Console da tela da impressora: as últimas linhas do console (comandos e respostas,
 * lidas a cada 2 s enquanto a aba está aberta) e um campo para mandar G-code livre, com os
 * comandos da impressora como sugestão. O G-code vai como está, sem as verificações das outras
 * abas; durante a impressão pede confirmação. O envio não ocupa os comandos da tela, então a
 * parada de emergência segue livre enquanto um comando demora.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrinterConsoleTab(host: PrinterHost, status: PrinterStatus?, viewModel: SlicerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember(host) { mutableStateOf(emptyList<ConsoleLine>()) }
    var refresh by remember(host) { mutableIntStateOf(0) }
    LaunchedEffect(host, refresh) {
        while (true) {
            try {
                lines = viewModel.printerConsole(host, CONSOLE_LINES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SendException) {
                // Fica com as últimas; o estado acima já mostra a falha de conexão.
                Log.w("Console", "console of ${host.name}", e)
            }
            delay(CONSOLE_POLL_MS)
        }
    }
    var commands by remember(host) { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(host) {
        try {
            commands = viewModel.printerGcodeCommands(host)
        } catch (e: SendException) {
            Log.w("Console", "commands of ${host.name}", e)
        }
    }

    var text by rememberSaveable(host.id) { mutableStateOf("") }
    var sending by remember(host) { mutableStateOf(false) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    var confirm by remember(host) { mutableStateOf<String?>(null) }
    val ready = status != null && status.state != PrinterStatus.State.OFFLINE
    fun send(script: String) {
        sending = true
        failure = null
        scope.launch {
            try {
                viewModel.sendPrinterGcode(host, script)
                text = ""
            } catch (e: SendException) {
                failure = context.getString(e.text, *e.args)
            } finally {
                sending = false
                refresh++
            }
        }
    }
    fun submit() {
        val script = text.trim()
        if (script.isEmpty() || sending || !ready) return
        if (status?.busy == true) confirm = script else send(script)
    }

    Banner(Tone.WARNING, stringResource(R.string.console_warning), Modifier.widthIn(max = 720.dp).fillMaxWidth())
    ConsoleLog(lines, Modifier.widthIn(max = 720.dp).fillMaxWidth())
    // Acima do campo, para o teclado não cobrir. O comando que se digita: as que começam assim, e a descrição quando é exatamente um.
    val word = text.trimStart().substringBefore(' ').uppercase()
    if (word.isNotEmpty() && !text.trimStart().contains(' ')) {
        val matches = commands.keys.filter { it.startsWith(word) && it != word }.sorted().take(SUGGESTIONS)
        if (matches.isNotEmpty()) {
            FlowRow(Modifier.widthIn(max = 720.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (match in matches) AssistChip(onClick = { text = "$match " }, label = { Text(match, style = MonoStyles.small) })
            }
        }
    }
    commands[word]?.takeIf { it.isNotBlank() }?.let {
        Text("$word — $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField(
            text, { text = it }, stringResource(R.string.console_command), Modifier.weight(1f), monospace = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        AppButton(stringResource(R.string.console_send), ::submit, icon = R.drawable.ic_send, enabled = ready && !sending && text.isNotBlank())
    }
    failure?.let { Banner(Tone.ERROR, it, Modifier.widthIn(max = 720.dp).fillMaxWidth(), onDismiss = { failure = null }) }

    confirm?.let { script ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.console_confirm_title)) },
            text = { Text(stringResource(R.string.console_confirm_text, script)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    send(script)
                }) { Text(stringResource(R.string.console_send)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** As linhas, a mais nova embaixo e à vista; os comandos destacados, os erros do Klipper (`!!`) em vermelho. */
@Composable
private fun ConsoleLog(lines: List<ConsoleLine>, modifier: Modifier) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(lines.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
    Column(
        modifier.height(360.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .verticalScroll(scroll).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (lines.isEmpty()) Text(stringResource(R.string.console_empty), style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val time = DateFormat.getTimeFormat(context)
        for (line in lines) {
            val color = when {
                line.command -> MaterialTheme.colorScheme.primary
                line.message.startsWith("!!") -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(time.format(Date(line.time)), style = MonoStyles.small, color = MaterialTheme.colorScheme.outline)
                Text(if (line.command) "> ${line.message}" else line.message, style = MonoStyles.small, color = color)
            }
        }
    }
}
