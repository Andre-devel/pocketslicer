package io.github.andredevel.slicer.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Viewport 3D. Gestos: 1 dedo gira a câmera; 2 dedos movem (pan) e aproximam (pinça).
 */
@SuppressLint("ViewConstructor")
class ViewportView(context: Context) : GLSurfaceView(context) {
    private val renderer = SceneRenderer()

    // Último ponto de referência do gesto: centroide e distância entre os dedos.
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastPointers = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setScene(scene: Scene?) = onGlThread { renderer.scene = scene }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointers = minOf(event.pointerCount, 2)
        // Ao levantar um dedo ele ainda conta neste evento; o gesto recomeça no próximo.
        val lifting = event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP
        val x = (0 until pointers).sumOf { event.getX(it).toDouble() }.toFloat() / pointers
        val y = (0 until pointers).sumOf { event.getY(it).toDouble() }.toFloat() / pointers
        val span = if (pointers == 2) hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)) else 0f

        if (event.actionMasked == MotionEvent.ACTION_MOVE && pointers == lastPointers) {
            val dx = x - lastX
            val dy = y - lastY
            val zoom = if (pointers == 2 && lastSpan > 0f && span > 0f) span / lastSpan else 1f
            onGlThread {
                val camera = renderer.camera
                if (pointers == 1) {
                    camera.rotate(-dx * ROTATE_DEG_PER_PX, dy * ROTATE_DEG_PER_PX)
                } else {
                    camera.pan(dx, dy, renderer.viewportHeight)
                    camera.zoom(zoom)
                }
            }
        }
        lastX = x
        lastY = y
        lastSpan = span
        lastPointers = if (lifting) 0 else pointers
        return true
    }

    private fun onGlThread(block: () -> Unit) {
        queueEvent(block)
        requestRender()
    }

    private companion object {
        const val ROTATE_DEG_PER_PX = 0.3f
    }
}
