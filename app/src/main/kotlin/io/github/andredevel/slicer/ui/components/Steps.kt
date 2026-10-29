package io.github.andredevel.slicer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.R
import io.github.andredevel.slicer.ui.theme.MonoStyles
import io.github.andredevel.slicer.ui.theme.PocketTheme

enum class StepStatus { DONE, CURRENT, PENDING }

/** Linha de uma lista de etapas: ✓, girando ou ○, o nome e um [detail] em mono à direita. */
@Composable
fun StepRow(label: String, status: StepStatus, modifier: Modifier = Modifier, detail: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.DONE -> Icon(painterResource(R.drawable.ic_check_circle), null, tint = PocketTheme.colors.success)
                StepStatus.CURRENT -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                StepStatus.PENDING -> Icon(painterResource(R.drawable.ic_radio_button_unchecked), null, tint = scheme.outline)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (status == StepStatus.PENDING) scheme.onSurfaceVariant else scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) Text(detail, style = MonoStyles.small, color = scheme.onSurfaceVariant)
    }
}
