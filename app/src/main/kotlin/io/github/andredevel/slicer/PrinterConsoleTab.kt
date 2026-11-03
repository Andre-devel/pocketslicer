package io.github.andredevel.slicer

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.util.Date

/** Linhas do console lidas de cada vez. */
private const val CONSOLE_LINES = 200
private const val CONSOLE_POLL_MS = 2_000L

/** Sugestões de comando mostradas enquanto se digita. */
private const val SUGGESTIONS = 4
private const val SUGGESTIONS_NARROW = 2

/**
 * Aba Console da tela da impressora: as últimas linhas do console (comandos e respostas,
 * lidas a cada 2 s enquanto a aba está aberta) e um campo para mandar G-code livre, com os
 * comandos da impressora como sugestão. O G-code vai como está, sem as verificações das outras
 * abas; durante a impressão pede confirmação. O envio não ocupa os comandos da tela, então a
 * parada de emergência segue livre enquanto um comando demora.
 */
@Composable
internal fun PrinterConsoleTab(host: PrinterHost, status: PrinterStatus?, console: ConsoleSend, viewModel: SlicerViewModel) {
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
    var confirm by remember(host) { mutableStateOf<String?>(null) }
    val sending = console.sending
    val ready = status != null && status.state != PrinterStatus.State.OFFLINE
    // O campo esvazia ao mandar, como num terminal (o comando aparece no console); o que falhou
    // volta para o campo se ele estiver vazio, para corrigir e mandar de novo.
    LaunchedEffect(console.failed) {
        console.failed?.let { if (text.isEmpty()) text = it }
    }
    // Terminou: lê o console já, sem esperar a próxima consulta.
    LaunchedEffect(sending) {
        if (!sending) refresh++
    }
    fun send(script: String) {
        text = ""
        viewModel.sendConsoleGcode(host, script)
    }
    fun submit() {
        val script = text.trim()
        if (script.isEmpty() || sending || !ready) return
        if (status?.busy == true) confirm = script else send(script)
    }

    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        // O console ocupa o que sobra da tela; o campo e as sugestões ficam embaixo dele, acima do teclado.
        val logHeight = (LocalConfiguration.current.screenHeightDp.dp - if (wide) 340.dp else 380.dp).coerceAtLeast(220.dp)
        Column(verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 8.dp)) {
            Banner(Tone.WARNING, stringResource(R.string.console_warning), Modifier.fillMaxWidth())
            ConsoleLog(lines, Modifier.fillMaxWidth().height(logHeight))
            // O comando que se digita: os que começam assim, com a descrição; o primeiro em destaque.
            val word = text.trimStart().substringBefore(' ').uppercase()
            if (word.isNotEmpty() && !text.trimStart().contains(' ')) {
                val matches = commands.keys.filter { it.startsWith(word) && it != word }.sorted().take(if (wide) SUGGESTIONS else SUGGESTIONS_NARROW)
                if (matches.isNotEmpty()) Suggestions(matches, commands, Modifier.widthIn(max = 620.dp).fillMaxWidth()) { text = "$it " }
            }
            commands[word]?.takeIf { it.isNotBlank() }?.let {
                Text("$word — $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(
                    text, { text = it }, stringResource(R.string.console_command), Modifier.weight(1f), monospace = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                val canSend = ready && !sending && text.isNotBlank()
                if (wide) {
                    AppButton(stringResource(R.string.console_send), ::submit, icon = R.drawable.ic_send, enabled = canSend)
                } else {
                    FilledIconButton(::submit, Modifier.size(52.dp), enabled = canSend) {
                        Icon(painterResource(R.drawable.ic_send), stringResource(R.string.console_send))
                    }
                }
            }
            console.error?.let { Banner(Tone.ERROR, it, Modifier.fillMaxWidth(), onDismiss = { viewModel.dismissConsoleFailure(host) }) }
        }
    }

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

/** Comandos que começam com o que se digita, com a descrição da impressora; tocar completa o campo. */
@Composable
private fun Suggestions(matches: List<String>, commands: Map<String, String>, modifier: Modifier, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(4.dp)) {
            matches.forEachIndexed { i, match ->
                val first = i == 0
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (first) colors.primaryContainer else Color.Transparent)
                        .clickable { onPick(match) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(match, style = MonoStyles.value, color = if (first) colors.onPrimaryContainer else colors.onSurface, maxLines = 1)
                    commands[match]?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** As linhas, a mais nova embaixo e à vista; os comandos destacados, os erros do Klipper (`!!`) em vermelho. */
@Composable
private fun ConsoleLog(lines: List<ConsoleLine>, modifier: Modifier) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(lines.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
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
                Text(if (line.command) "› ${line.message}" else line.message, style = MonoStyles.small, color = color)
            }
        }
    }
}
