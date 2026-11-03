package io.github.andredevel.slicer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.send.BedArea
import io.github.andredevel.slicer.send.PrintObject
import io.github.andredevel.slicer.send.PrinterCommand
import io.github.andredevel.slicer.send.PrinterStatus
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.ContentCard
import io.github.andredevel.slicer.ui.theme.MonoStyles
import kotlin.math.hypot

/**
 * Objetos da impressão em andamento ([PrinterStatus.objects]): a mesa vista de cima com o
 * contorno de cada um (o que está sendo impresso em destaque, os cancelados apagados) e a lista
 * deles. Tocar num objeto da mesa, ou em Cancelar na lista, pede confirmação e o cancela
 * ([PrinterCommand.ExcludeObject]); a lista serve para os pequenos demais para o dedo.
 */
@Composable
internal fun PrintObjects(status: PrinterStatus, commands: CommandRunner, modifier: Modifier = Modifier) {
    val enabled = !status.halted && status.state != PrinterStatus.State.OFFLINE && !commands.sending
    var confirm by remember { mutableStateOf<String?>(null) }
    ContentCard(modifier, title = stringResource(R.string.print_objects), subtitle = stringResource(R.string.print_objects_hint)) {
    val bed = status.bed ?: boundsOf(status.objects)
    if (bed != null && status.objects.any { it.polygon.size >= 3 || it.center != null }) {
        BedMap(bed, status.objects, Modifier.widthIn(max = 480.dp).fillMaxWidth()) { name -> if (enabled) confirm = name }
    }
    for (obj in status.objects) {
        // O que está sendo impresso agora em destaque, como na mesa.
        val row = if (obj.current) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small) else Modifier
        Row(Modifier.fillMaxWidth().then(row).heightIn(min = 52.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(obj.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                obj.excluded -> Text(stringResource(R.string.print_object_excluded), style = MonoStyles.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    if (obj.current) Text(stringResource(R.string.print_object_current), style = MonoStyles.small, color = MaterialTheme.colorScheme.primary)
                    AppButton(stringResource(R.string.print_object_cancel), { confirm = obj.name }, style = ButtonStyle.OUTLINED, enabled = enabled, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    }
    // Não tem volta: confirma com o nome.
    confirm?.let { name ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.print_object_cancel_title, name)) },
            text = { Text(stringResource(R.string.print_object_cancel_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    commands.run(PrinterCommand.ExcludeObject(name))
                }) { Text(stringResource(R.string.print_object_cancel_confirm), color = MaterialTheme.colorScheme.error) }
            },
            // "Cancelar" aqui seria ambíguo com o que o diálogo faz.
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.print_object_keep)) } },
        )
    }
}

/** A mesa de cima (Y para cima, como na impressora) com os objetos; [onTap] com o nome do objeto tocado que ainda não foi cancelado. */
@Composable
private fun BedMap(bed: BedArea, objects: List<PrintObject>, modifier: Modifier, onTap: (String) -> Unit) {
    val width = bed.maxX - bed.minX
    val height = bed.maxY - bed.minY
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.print_objects_map)
    Canvas(
        modifier.aspectRatio(width / height)
            .semantics { contentDescription = description }
            .pointerInput(objects, bed) {
                detectTapGestures { tap ->
                    val x = bed.minX + tap.x / size.width * width
                    val y = bed.maxY - tap.y / size.height * height
                    val candidates = objects.filter { !it.excluded }
                    // Dentro do contorno; sem nenhum, o centro mais perto num raio de ~1 cm na tela.
                    val hit = candidates.firstOrNull { it.polygon.size >= 3 && inside(it.polygon, x, y) }
                        ?: candidates.mapNotNull { o -> o.center?.let { o to hypot(it.first - x, it.second - y) } }
                            .filter { (_, d) -> d / width * size.width < 48.dp.toPx() }.minByOrNull { it.second }?.first
                    hit?.let { onTap(it.name) }
                }
            },
    ) {
        fun at(p: Pair<Float, Float>) = Offset((p.first - bed.minX) / width * size.width, (bed.maxY - p.second) / height * size.height)
        drawRect(surface)
        drawRect(outline, style = Stroke(1.dp.toPx()))
        for (obj in objects) {
            val color = when {
                obj.excluded -> muted.copy(alpha = 0.35f)
                obj.current -> primary
                else -> primary.copy(alpha = 0.55f)
            }
            if (obj.polygon.size >= 3) {
                val path = Path().apply {
                    obj.polygon.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    close()
                }
                drawPath(path, color.copy(alpha = color.alpha * 0.4f))
                drawPath(path, color, style = Stroke(if (obj.current) 2.5.dp.toPx() else 1.5.dp.toPx()))
            } else {
                obj.center?.let { drawCircle(color, 6.dp.toPx(), at(it)) }
            }
        }
    }
}

/** Sem a área da mesa: a caixa dos objetos com uma margem. */
private fun boundsOf(objects: List<PrintObject>): BedArea? {
    val points = objects.flatMap { it.polygon + listOfNotNull(it.center) }
    if (points.isEmpty()) return null
    val margin = 20f
    return BedArea(points.minOf { it.first } - margin, points.minOf { it.second } - margin, points.maxOf { it.first } + margin, points.maxOf { it.second } + margin)
}

/** Ponto dentro do polígono (raio cruzando as arestas). */
private fun inside(polygon: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (xi, yi) = polygon[i]
        val (xj, yj) = polygon[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}
