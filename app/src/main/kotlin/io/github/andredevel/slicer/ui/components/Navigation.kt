package io.github.andredevel.slicer.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.ui.theme.PocketTheme

/** Um destino da navegação principal. */
data class NavItem(@DrawableRes val icon: Int, val label: String, val enabled: Boolean = true)

/** Navegação lateral (tablet, paisagem): [header] no topo, [footer] encostado embaixo. */
@Composable
fun AppNavigationRail(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    footer: @Composable (ColumnScope.() -> Unit)? = null,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight().width(88.dp),
        containerColor = PocketTheme.colors.navigation,
        header = header,
    ) {
        Spacer(Modifier.padding(top = 4.dp))
        items.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = index == selected,
                onClick = { if (index != selected) onSelect(index) },
                enabled = item.enabled,
                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        if (footer != null) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, content = footer)
        }
    }
}

/** Navegação inferior (celular, retrato). */
@Composable
fun AppNavigationBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier, containerColor = PocketTheme.colors.navigation) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selected,
                onClick = { if (index != selected) onSelect(index) },
                enabled = item.enabled,
                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** Marca do app: quadrado laranja com as iniciais. */
@Composable
fun AppMark(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(vertical = 12.dp).size(44.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}
