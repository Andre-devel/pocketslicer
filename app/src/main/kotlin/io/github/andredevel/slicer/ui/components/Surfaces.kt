package io.github.andredevel.slicer.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.ui.theme.MonoStyles

/** Rótulo de seção em caixa alta e mono ("PERFIL"); [trailing] para ações da seção. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MonoStyles.section,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** Cartão que flutua sobre o viewport 3D: fundo quase opaco e borda fina. */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/** Bloco de informação discreto (fundo um tom acima do painel), opcionalmente com ícone. */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier, title: String? = null, @DrawableRes icon: Int? = null) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Etiqueta curta em pílula ("Modificado · 3", "Perfil do fatiamento"). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = modifier.background(container, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Contador redondo (alterações por tipo de preset). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(min = 18.dp).heightIn(min = 18.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = MonoStyles.small, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Ponto de estado com texto colorido ("● Livre"). */
@Composable
fun StatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Medida de um resumo: rótulo pequeno, valor mono e, embaixo, um detalhe. */
@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoStyles.title, maxLines = 1)
        if (detail != null) Text(detail, style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Estado vazio: ícone num quadrado tonal, título, explicação e ações. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier.widthIn(max = 440.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        }
    }
}

/** Coluna de painel lateral com o espaçamento padrão entre blocos. */
@Composable
fun PanelColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
