package io.github.andredevel.slicer.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.R
import io.github.andredevel.slicer.ui.theme.PocketTheme

/** Tom de uma mensagem: define fundo, cor do ícone e do texto. */
enum class Tone { ERROR, WARNING, INFO, SUCCESS }

private data class ToneColors(val container: Color, val icon: Color, val content: Color)

@Composable
private fun toneColors(tone: Tone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val extended = PocketTheme.colors
    return when (tone) {
        Tone.ERROR -> ToneColors(scheme.errorContainer, scheme.error, scheme.onErrorContainer)
        Tone.WARNING -> ToneColors(extended.warningContainer, extended.warning, extended.onWarningContainer)
        Tone.INFO -> ToneColors(extended.infoContainer, extended.info, extended.onInfoContainer)
        Tone.SUCCESS -> ToneColors(scheme.surfaceContainerHigh, extended.success, scheme.onSurface)
    }
}

@DrawableRes
private fun Tone.defaultIcon() = when (this) {
    Tone.ERROR -> R.drawable.ic_error
    Tone.WARNING -> R.drawable.ic_warning
    Tone.INFO -> R.drawable.ic_info
    Tone.SUCCESS -> R.drawable.ic_check_circle
}

/** Cor de destaque do tom (texto ou ícone sobre o fundo do painel). */
@Composable
fun Tone.color(): Color = toneColors(this).icon

/**
 * Mensagem em bloco: ícone, [title] opcional em negrito, [text] e uma ação ([actionLabel]) à
 * direita. Usada para erros, avisos e resultados de operações.
 */
@Composable
fun Banner(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    @DrawableRes icon: Int = tone.defaultIcon(),
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val colors = toneColors(tone)
    Row(
        modifier.background(colors.container, MaterialTheme.shapes.medium).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colors.icon)
        Column(Modifier.weight(1f, fill = false).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = colors.content)
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.content)
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = colors.content)) {
                Text(actionLabel)
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = colors.content)
            }
        }
    }
}
