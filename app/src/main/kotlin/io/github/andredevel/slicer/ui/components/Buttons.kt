package io.github.andredevel.slicer.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.R

/** Hierarquia de botões: um só [PRIMARY] (laranja) por tela. */
enum class ButtonStyle { PRIMARY, TONAL, OUTLINED, TEXT }

/** Botão com ícone opcional, altura mínima de 48 dp e a hierarquia de [style]. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    color: Color? = null,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val sized = modifier.heightIn(min = 48.dp)
    val padding = PaddingValues(start = if (icon != null) 18.dp else 22.dp, end = 22.dp)
    when (style) {
        ButtonStyle.PRIMARY -> Button(
            onClick, sized, enabled, contentPadding = padding,
            colors = if (color != null) ButtonDefaults.buttonColors(containerColor = color) else ButtonDefaults.buttonColors(),
        ) { content() }
        ButtonStyle.TONAL -> FilledTonalButton(
            onClick, sized, enabled, contentPadding = padding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { content() }
        ButtonStyle.OUTLINED -> OutlinedButton(
            onClick, sized, enabled, contentPadding = padding,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color ?: MaterialTheme.colorScheme.onSurface),
        ) { content() }
        ButtonStyle.TEXT -> TextButton(
            onClick, sized, enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = color ?: MaterialTheme.colorScheme.onSurface),
        ) { content() }
    }
}

/** Botão só de ícone (48 dp); [tonal] para o estado aberto/ativo. */
@Composable
fun AppIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    tint: Color? = null,
) {
    if (tonal) {
        FilledTonalIconButton(onClick, modifier, enabled) { Icon(painterResource(icon), contentDescription) }
    } else {
        IconButton(onClick, modifier, enabled) {
            Icon(painterResource(icon), contentDescription, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Ferramenta vertical (ícone sobre rótulo), como as do viewport. */
@Composable
fun ToolButton(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.outline
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier
            .widthIn(min = 56.dp)
            .heightIn(min = 56.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, MaterialTheme.shapes.small)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Coluna de [ToolButton]s num [FloatingCard]. */
@Composable
fun ToolColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FloatingCard(modifier) {
        Column(Modifier.width(IntrinsicSize.Max).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** Ação em cápsula sobre o viewport ("4 objetos", "Arranjar"). */
@Composable
fun FloatingPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(24.dp)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** [FloatingPill] que abre um menu de [options]; [selected] ganha ✓ (-1: nenhuma). */
@Composable
fun MenuPill(
    text: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = R.drawable.ic_arrow_drop_down,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FloatingPill(text, { open = true }, icon = icon)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onSelect(index)
                    },
                    trailingIcon = if (index == selected) {
                        { Icon(painterResource(R.drawable.ic_check), null) }
                    } else null,
                )
            }
        }
    }
}
