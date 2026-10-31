package io.github.andredevel.slicer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.R
import io.github.andredevel.slicer.ui.theme.MonoStyles
import io.github.andredevel.slicer.ui.theme.PocketTheme
import java.util.Locale

/**
 * Campo compacto que só aplica o valor ao confirmar no teclado ou ao perder o foco
 * ([onCommit] com o texto editado); enquanto isso o texto digitado fica local.
 * [prefix] colorido à esquerda (eixo) e [suffix] (unidade) à direita, como no design.
 */
@Composable
fun CommitField(
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    prefixColor: Color = Color.Unspecified,
    suffix: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    textStyle: TextStyle = MonoStyles.value,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 8 else 1,
        textStyle = textStyle.copy(color = if (enabled) scheme.onSurface else scheme.outline),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused && text != value) onCommit(text)
            focused = it.isFocused
        },
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = LocalFieldHeight.current)
                    .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .border(BorderStroke(1.dp, if (focused) scheme.primary else scheme.outlineVariant), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = if (multiline) 12.dp else 0.dp),
                verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
            ) {
                if (prefix != null) {
                    Text(prefix, style = MonoStyles.value, color = prefixColor)
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) { inner() }
                if (suffix != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(suffix, style = MaterialTheme.typography.bodySmall, color = scheme.outline, maxLines = 1)
                }
            }
        },
    )
}

/** [CommitField] decimal: mostra com [decimals] casas no idioma do aparelho, aceita vírgula ou ponto. */
@Composable
fun NumberField(
    value: Float,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    prefixColor: Color = Color.Unspecified,
    suffix: String? = null,
    decimals: Int = 2,
    min: Float? = null,
    enabled: Boolean = true,
) {
    val formatted = format(value, decimals)
    CommitField(
        value = formatted,
        onCommit = { text ->
            val parsed = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { min == null || it >= min }
            if (parsed != null && format(parsed, decimals) != formatted) onCommit(parsed)
        },
        modifier = modifier,
        prefix = prefix,
        prefixColor = prefixColor,
        suffix = suffix,
        enabled = enabled,
        keyboardType = KeyboardType.Decimal,
    )
}

/** [decimals] casas no idioma do aparelho, sem o "-" de valores que arredondam para zero. */
private fun format(value: Float, decimals: Int): String {
    val text = String.format(Locale.getDefault(), "%.${decimals}f", value)
    return if (text.none { it in '1'..'9' }) text.removePrefix("-") else text
}

/** Uma linha de [NumberField]s X/Y/Z com as cores dos eixos; [axes] = 2 para posição na mesa. */
@Composable
fun AxisFields(
    values: FloatArray,
    onCommit: (axis: Int, value: Float) -> Unit,
    modifier: Modifier = Modifier,
    axes: Int = 3,
    suffix: String? = null,
    min: Float? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (axis in 0 until axes) {
            NumberField(
                values[axis], { onCommit(axis, it) }, Modifier.weight(1f),
                prefix = "XYZ"[axis].toString(), prefixColor = PocketTheme.colors.axes[axis],
                suffix = suffix, min = min, enabled = enabled,
            )
        }
    }
}

/** Número inteiro com botões − e +; o valor digitado vale ao confirmar. */
@Composable
fun StepperField(value: Int, onCommit: (Int) -> Unit, modifier: Modifier = Modifier, min: Int? = null, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = LocalFieldHeight.current)
            .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onCommit(value - 1) }, Modifier.size(LocalFieldHeight.current), enabled = enabled && (min == null || value > min)) {
            Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.decrease))
        }
        CommitField(
            value = "$value",
            onCommit = { text -> text.trim().toIntOrNull()?.takeIf { min == null || it >= min }?.let(onCommit) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            keyboardType = KeyboardType.Number,
        )
        IconButton(onClick = { onCommit(value + 1) }, Modifier.size(LocalFieldHeight.current), enabled = enabled) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.increase))
        }
    }
}

/** Campo de formulário com rótulo (cadastro de impressora, salvar preset). */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = true,
        isError = isError,
        textStyle = if (monospace) MonoStyles.value else MaterialTheme.typography.bodyLarge,
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.small,
        colors = fieldColors(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

/**
 * Lista de escolha: [options] com os textos [labels] (os próprios valores se vazio); [onSelect]
 * só é chamado ao trocar. Sem [label], fica compacto como os editores de parâmetros.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectField(
    value: String?,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labels: List<String> = options,
    enabled: Boolean = true,
    prefix: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = options.indexOf(value).takeIf { it >= 0 }?.let(labels::getOrNull) ?: value.orEmpty()
    val canOpen = enabled && options.isNotEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && canOpen }, modifier = modifier) {
        val anchor = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, canOpen)
        val arrow = @Composable {
            Icon(painterResource(R.drawable.ic_arrow_drop_down), null, Modifier.rotate(if (expanded) 180f else 0f))
        }
        if (label != null) {
            OutlinedTextField(
                value = (prefix ?: "") + shown,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = arrow,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = anchor,
            )
        } else {
            val scheme = MaterialTheme.colorScheme
            Row(
                anchor
                    .heightIn(min = LocalFieldHeight.current)
                    .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .border(1.dp, if (expanded) scheme.primary else scheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (prefix ?: "") + shown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) scheme.onSurface else scheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(24.dp)) { arrow() }
            }
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                val selected = option == value
                DropdownMenuItem(
                    text = {
                        Text(
                            labels.getOrElse(index) { option },
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    trailingIcon = if (selected) ({ Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = {
                        expanded = false
                        if (!selected) onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * Altura dos campos compactos (valor, número, lista sem rótulo); menor nas listas densas, como
 * os parâmetros no painel lateral do Preparar.
 */
val LocalFieldHeight = staticCompositionLocalOf { 44.dp }
