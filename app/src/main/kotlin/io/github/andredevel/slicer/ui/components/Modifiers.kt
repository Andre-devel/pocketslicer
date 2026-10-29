package io.github.andredevel.slicer.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Gira o conteúdo 90° no sentido anti-horário trocando largura e altura no layout: um Slider
 * horizontal vira vertical (início embaixo). Ex.: as camadas da pré-visualização.
 */
fun Modifier.vertical(): Modifier = graphicsLayer {
    rotationZ = 270f
    transformOrigin = TransformOrigin(0f, 0f)
}.layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth,
        ),
    )
    layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
}

/** Consome os toques: o que está embaixo (o viewport 3D) não os recebe. */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
