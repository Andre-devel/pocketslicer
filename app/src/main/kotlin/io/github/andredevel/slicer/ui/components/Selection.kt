package io.github.andredevel.slicer.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.R

/** Opção de um [SegmentedTabs]: texto e, opcionalmente, um contador. */
data class Segment(val label: String, val count: Int? = null, val enabled: Boolean = true)

/**
 * Escolha única em segmentos (Processo/Filamento/Impressora, Básico/Avançado, modo de cor):
 * o escolhido fica tonal laranja com ✓ ([check]).
 */
@Composable
fun SegmentedTabs(
    segments: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    check: Boolean = true,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape),
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) VerticalDivider(color = scheme.outlineVariant)
            val isSelected = index == selected
            val content = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) scheme.primaryContainer else Color.Transparent)
                    .selectable(isSelected, enabled = segment.enabled, role = Role.Tab) { if (!isSelected) onSelect(index) }
                    .padding(horizontal = if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                if (check && isSelected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = content)
                Text(
                    segment.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (segment.enabled) content else scheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                segment.count?.takeIf { it > 0 }?.let { CountBadge(it) }
            }
        }
    }
}

/** Pílulas lado a lado (placas do projeto); a escolhida fica clara sobre o fundo. */
@Composable
fun PillTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    FloatingCard(modifier, RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Sem espaço para todas, as abas rolam; [trailing] fica sempre à vista.
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selected
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            isSelected -> scheme.inverseOnSurface
                            enabled(index) -> scheme.onSurface
                            else -> scheme.outline
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) scheme.inverseSurface else Color.Transparent)
                            .selectable(isSelected, enabled = enabled(index), role = Role.Tab) { if (!isSelected) onSelect(index) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            trailing()
        }
    }
}

/**
 * Cartão selecionável de uma lista (impressoras, itens de configuração). [accent]: o escolhido
 * ganha borda e fundo laranja (escolha que será usada); senão, só um fundo mais claro.
 */
@Composable
fun SelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    radio: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val background = when {
        selected && accent -> scheme.primaryContainer
        selected -> scheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(if (selected && accent) Modifier.border(BorderStroke(1.dp, scheme.primary), shape) else Modifier)
            .selectable(selected, enabled = enabled, role = if (radio) Role.RadioButton else Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (radio) RadioButton(selected = selected, onClick = null, enabled = enabled)
        content()
    }
}

/**
 * Linha de navegação para um perfil: ícone, rótulo pequeno ([overline]), valor e um [tag]
 * opcional ao lado; seta à direita.
 */
@Composable
fun NavigationRow(
    @DrawableRes icon: Int,
    overline: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(overline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag != null) Tag(tag)
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Linha de propriedade de formulário com [content] à direita (switch, valor). */
@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, detail: String? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/** Separador vertical fino entre colunas de um layout de várias colunas. */
@Composable
fun ColumnDivider() = VerticalDivider(Modifier.width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
