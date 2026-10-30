package io.github.andredevel.slicer.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import io.github.andredevel.slicer.core.ClipPlane
import io.github.andredevel.slicer.core.ObjectMesh
import io.github.andredevel.slicer.core.PaintKind
import io.github.andredevel.slicer.core.Part
import io.github.andredevel.slicer.core.Thumbnail
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.view.ViewConfiguration
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Ação de um arraste de 1 dedo que começa sobre o objeto selecionado (ou de um toque, em
 * [LAY_ON_FACE]). [paintKind]: as de pintura, em que tocar e arrastar no objeto pinta.
 */
enum class Tool(val paintKind: PaintKind? = null) {
    MOVE, ROTATE, SCALE, LAY_ON_FACE, PAINT_SUPPORT(PaintKind.SUPPORT), PAINT_SEAM(PaintKind.SEAM),
    PAINT_FUZZY_SKIN(PaintKind.FUZZY_SKIN), PAINT_COLOR(PaintKind.COLOR)
}

/** Resultados dos gestos, entregues na thread principal. */
interface ViewportListener {
    /** Toque num objeto (índice) ou fora deles (null); [part]: numa peça dele que não é do objeto. */
    fun onSelect(objectIndex: Int?, part: Int?)
    /** Fim de um arraste que moveu, girou ou escalou o objeto. */
    fun onTransform(objectIndex: Int, transform: FloatArray)
    /** Fim de um arraste da peça selecionada: a matriz nova dela, na do objeto. */
    fun onPartTransform(objectIndex: Int, part: Int, transform: FloatArray)
    /**
     * Toque ou ponto de um arraste no objeto selecionado com uma ferramenta de pintura: o raio
     * ([origin], [direction], na mesa) pelo dedo; [strokeStart] no primeiro do arraste; [clip], a
     * vista em corte em uso.
     */
    fun onPaint(objectIndex: Int, origin: FloatArray, direction: FloatArray, strokeStart: Boolean, clip: ClipPlane?)
    /** Toque numa face do objeto selecionado com [Tool.LAY_ON_FACE]. */
    fun onLayOnFace(objectIndex: Int, triangle: Int)
    /** Fim de um arraste da torre de purga: canto da frente à esquerda na mesa. */
    fun onWipeTowerMoved(x: Float, y: Float)
    /** Toque longo num objeto, em (x, y) pixels do viewport. */
    fun onLongPress(objectIndex: Int, x: Float, y: Float)
    /** A câmera foi para uma vista pronta, ou saiu dela (null) com um gesto. */
    fun onCameraView(view: CameraView?)
}

/**
 * Viewport 3D. Gestos: toque seleciona (um objeto ou uma peça); toque longo abre o menu do
 * objeto; 1 dedo gira a câmera, ou aplica [tool] se o arraste começar sobre a peça ou o objeto
 * selecionado, ou move a torre de purga se começar nela; 2 dedos movem (pan) e aproximam (pinça).
 * Com uma ferramenta de pintura, tocar e arrastar no objeto selecionado pinta, e tocar fora dele
 * não muda a seleção.
 *
 * Os eventos de toque são interpretados na thread GL (queueEvent), onde estão a
 * câmera e a cena; o estado do arraste (campos "drag*") só é usado nela.
 */
@SuppressLint("ViewConstructor")
class ViewportView(context: Context) : GLSurfaceView(context) {
    private val renderer = SceneRenderer(
        dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    )

    /** Tema do app, que pode diferir do sistema (Ajustes). */
    var dark: Boolean = renderer.dark
        set(value) {
            if (value == field) return
            field = value
            onGlThread { renderer.dark = value }
        }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var listener: ViewportListener? = null
    var tool = Tool.MOVE

    // Thread principal: referência do gesto (centroide e distância entre os dedos).
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastPointers = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        val x = downX
        val y = downY
        onGlThread {
            // O dedo parado sobre o objeto não deve virar um arraste.
            dragObject = null
            renderer.dragTransform = null
            renderer.dragPartTransform = null
            val hit = pickAt(x, y) ?: return@onGlThread
            post { listener?.onLongPress(hit.objectIndex, x, y) }
        }
    }

    // Thread GL: arraste de objeto em andamento.
    private var dragObject: Int? = null
    /** Com [dragObject], a peça dele sendo arrastada (senão, o objeto). */
    private var dragPart: Int? = null
    private var dragTool = Tool.MOVE
    private var dragGrabZ = 0f
    private var dragLast: FloatArray? = null
    private var draggingTower = false
    /** Objeto sendo pintado no gesto (o selecionado, se ele começou sobre ele). */
    private var paintObject: Int? = null
    private var paintStarted = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private var scene: Scene? = null

    // Thread GL: última vista entregue ao listener.
    private var reportedView: CameraView? = null

    fun setView(view: CameraView) = onGlThread { renderer.setView(view) }

    /** Altura em pixels coberta embaixo pelo bottom sheet; as vistas enquadram acima dela. */
    fun setBottomInset(px: Int) = onGlThread { renderer.bottomInset = px }

    private var preview: Preview? = null
    private var showPreview = false
    private var previewSettings = PreviewSettings()

    /** Caminhos do G-code; com [show], o viewport mostra só eles e não edita objetos. */
    private var paint: PaintOverlay? = null

    /** Pintura do objeto selecionado, desenhada sobre ele com a ferramenta de pintura. */
    fun setPaint(paint: PaintOverlay?) {
        if (paint == this.paint) return
        this.paint = paint
        onGlThread { renderer.paint = paint }
    }

    fun setPreview(preview: Preview?, show: Boolean, settings: PreviewSettings) {
        if (preview === this.preview && show == showPreview && settings == previewSettings) return
        this.preview = preview
        showPreview = show
        previewSettings = settings
        onGlThread {
            renderer.preview = preview
            renderer.showPreview = show
            renderer.previewSettings = settings
        }
    }

    /**
     * Ver [SceneRenderer.requestThumbnail]; null sem objetos. Só termina com a superfície GL
     * ativa (app em primeiro plano).
     */
    suspend fun thumbnail(width: Int, height: Int): Thumbnail? = suspendCancellableCoroutine { continuation ->
        onGlThread {
            renderer.requestThumbnail(width, height) { rgba ->
                continuation.resume(rgba.takeIf { it.isNotEmpty() }?.let { Thumbnail(width, height, it) })
            }
        }
    }

    fun setScene(scene: Scene?) {
        // Recomposições repetem a mesma cena; reenviá-la descartaria a pré-visualização.
        if (scene == this.scene) return
        this.scene = scene
        onGlThread { renderer.scene = scene }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointers = minOf(event.pointerCount, 2)
        // Ao levantar um dedo ele ainda conta neste evento; o gesto recomeça no próximo.
        val lifting = event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP
        val x = (0 until pointers).sumOf { event.getX(it).toDouble() }.toFloat() / pointers
        val y = (0 until pointers).sumOf { event.getY(it).toDouble() }.toFloat() / pointers
        val span = if (pointers == 2) hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)) else 0f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                moved = false
                longPressed = false
                val tool = tool
                if (tool.paintKind == null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                onGlThread { startDrag(x, y, tool) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                removeCallbacks(longPress)
                onGlThread { endDrag() } // dois dedos sempre controlam a câmera
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - downX, y - downY) > touchSlop && !moved) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (moved && !longPressed && pointers == lastPointers) {
                    val dx = x - lastX
                    val dy = y - lastY
                    val zoom = if (pointers == 2 && lastSpan > 0f && span > 0f) span / lastSpan else 1f
                    onGlThread {
                        val camera = renderer.camera
                        when {
                            pointers == 2 -> {
                                camera.pan(dx, dy, renderer.viewportHeight)
                                camera.zoom(zoom)
                            }
                            paintObject != null -> {
                                // Só depois de passar do touch slop: um segundo dedo antes disso não pinta.
                                if (!paintStarted) paintAt(downX, downY, strokeStart = true)
                                paintAt(x, y, strokeStart = false)
                            }
                            dragObject != null -> drag(x, y, dx, dy)
                            draggingTower -> dragTower(x, y)
                            else -> camera.rotate(-dx * ROTATE_DEG_PER_PX, dy * ROTATE_DEG_PER_PX)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val tap = !moved && !longPressed
                val tool = tool
                val held = longPressed
                onGlThread { if (tap) tap(x, y, tool) else if (!held) endDrag() }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                onGlThread { endDrag() }
            }
        }
        lastX = x
        lastY = y
        lastSpan = span
        lastPointers = if (lifting) 0 else pointers
        return true
    }

    // --- Thread GL ---

    private fun pickAt(x: Float, y: Float): Hit? {
        // Na pré-visualização os objetos não aparecem: nada a tocar.
        if (renderer.showPreview) return null
        val scene = renderer.scene ?: return null
        val ray = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight)
        return pick(ray, scene.meshes, renderer.transforms())
    }

    /** Peça (que não é do objeto) mais próxima no toque: (objeto, peça) e o ponto. */
    private fun pickPart(x: Float, y: Float): Pair<Pair<Int, Int>, Hit>? {
        if (renderer.showPreview) return null
        val scene = renderer.scene ?: return null
        val ids = ArrayList<Pair<Int, Int>>()
        val meshes = ArrayList<Part>()
        val transforms = ArrayList<FloatArray>()
        for ((i, partTransforms) in renderer.partTransforms().withIndex()) {
            for ((j, transform) in partTransforms.withIndex()) {
                val part = scene.parts[i][j]
                if (part.type == Part.Type.MODEL) continue
                ids += i to j
                meshes += part
                transforms += transform
            }
        }
        val ray = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight)
        val hit = pick(ray, meshes, transforms) ?: return null
        return ids[hit.objectIndex] to hit
    }

    private fun startDrag(x: Float, y: Float, tool: Tool) {
        dragObject = null
        dragPart = null
        draggingTower = false
        paintObject = null
        paintStarted = false
        if (tool.paintKind != null) {
            val selected = renderer.scene?.selected
            paintObject = selected?.takeIf { pickAt(x, y)?.objectIndex == it }
            return
        }
        if (tool == Tool.LAY_ON_FACE) return
        val selected = renderer.scene?.selected
        val selectedPart = renderer.scene?.selectedPart
        val part = pickPart(x, y)
        if (selectedPart != null && part != null && part.first == selected to selectedPart) {
            dragObject = selected
            dragPart = selectedPart
            dragTool = tool
            dragGrabZ = part.second.point[2]
            dragLast = part.second.point
            return
        }
        val hit = pickAt(x, y)
        if (hit != null && hit.objectIndex == selected) {
            dragObject = selected
            dragTool = tool
            dragGrabZ = hit.point[2]
            dragLast = hit.point
            return
        }
        // A torre, se estiver na frente de qualquer objeto.
        val tower = pickTower(x, y) ?: return
        val origin = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight).origin
        fun distance(p: FloatArray) = hypot(hypot(p[0] - origin[0], p[1] - origin[1]), p[2] - origin[2])
        if (hit != null && distance(hit.point) < distance(tower.point)) return
        draggingTower = true
        dragGrabZ = tower.point[2]
        dragLast = tower.point
    }

    private fun pickTower(x: Float, y: Float): Hit? {
        if (renderer.showPreview) return null
        val transform = renderer.towerTransform() ?: return null
        val ray = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight)
        return pick(ray, listOf(TOWER_MESH), listOf(transform))
    }

    private fun dragTower(x: Float, y: Float) {
        val tower = renderer.scene?.wipeTower ?: return
        val ray = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight)
        val point = ray.intersectZ(dragGrabZ) ?: return
        val last = dragLast ?: return
        val current = renderer.dragTower ?: floatArrayOf(tower.x, tower.y)
        renderer.dragTower = floatArrayOf(current[0] + point[0] - last[0], current[1] + point[1] - last[1])
        dragLast = point
    }

    private fun drag(x: Float, y: Float, dx: Float, dy: Float) {
        val index = dragObject ?: return
        val part = dragPart
        val current = (if (part != null) renderer.partTransforms().getOrNull(index)?.getOrNull(part) else renderer.transforms().getOrNull(index)) ?: return
        // Pivô: origem da instância (centro do objeto ao carregar) ou da peça.
        val cx = current[12]
        val cy = current[13]
        val cz = current[14]
        val step = FloatArray(16)
        when (dragTool) {
            Tool.MOVE -> {
                // Ponto sob o dedo no plano horizontal em que o objeto foi agarrado.
                val ray = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight)
                val point = ray.intersectZ(dragGrabZ) ?: return
                val last = dragLast ?: return
                Matrix.setIdentityM(step, 0)
                Matrix.translateM(step, 0, point[0] - last[0], point[1] - last[1], 0f)
                dragLast = point
            }
            Tool.ROTATE -> {
                Matrix.setIdentityM(step, 0)
                Matrix.translateM(step, 0, cx, cy, cz)
                Matrix.rotateM(step, 0, dx * ROTATE_DEG_PER_PX, 0f, 0f, 1f)
                Matrix.translateM(step, 0, -cx, -cy, -cz)
            }
            Tool.SCALE -> {
                val factor = exp(-dy * SCALE_PER_PX)
                Matrix.setIdentityM(step, 0)
                Matrix.translateM(step, 0, cx, cy, cz)
                Matrix.scaleM(step, 0, factor, factor, factor)
                Matrix.translateM(step, 0, -cx, -cy, -cz)
            }
            Tool.LAY_ON_FACE, Tool.PAINT_SUPPORT, Tool.PAINT_SEAM, Tool.PAINT_FUZZY_SKIN, Tool.PAINT_COLOR -> return
        }
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, step, 0, current, 0)
        if (part != null) renderer.dragPartTransform = result else renderer.dragTransform = result
    }

    private fun paintAt(x: Float, y: Float, strokeStart: Boolean) {
        val index = paintObject ?: return
        paintStarted = true
        val ray = renderer.camera.ray(x, y, renderer.viewportWidth, renderer.viewportHeight)
        val clip = renderer.clipPlane()
        post { listener?.onPaint(index, ray.origin, ray.direction, strokeStart, clip) }
    }

    private fun endDrag() {
        paintObject = null
        if (draggingTower) {
            draggingTower = false
            val position = renderer.dragTower ?: return
            post { listener?.onWipeTowerMoved(position[0], position[1]) }
            return
        }
        val index = dragObject ?: return
        dragObject = null
        val part = dragPart
        if (part != null) {
            dragPart = null
            val world = renderer.dragPartTransform ?: return
            // Na do objeto: a inversa da matriz dele vezes a da peça na mesa.
            val inverse = FloatArray(16)
            if (!Matrix.invertM(inverse, 0, renderer.transforms().getOrNull(index) ?: return, 0)) return
            val local = FloatArray(16).also { Matrix.multiplyMM(it, 0, inverse, 0, world, 0) }
            post { listener?.onPartTransform(index, part, local) }
            return
        }
        val transform = renderer.dragTransform ?: return
        post { listener?.onTransform(index, transform) }
    }

    private fun tap(x: Float, y: Float, tool: Tool) {
        dragObject = null
        val hit = pickAt(x, y)
        val selected = renderer.scene?.selected
        if (renderer.showPreview) return
        if (tool.paintKind != null) {
            if (paintObject != null) paintAt(x, y, strokeStart = true)
            paintObject = null
            return
        }
        if (hit != null && tool == Tool.LAY_ON_FACE && hit.objectIndex == selected) {
            post { listener?.onLayOnFace(hit.objectIndex, hit.triangle) }
            return
        }
        // As peças do selecionado aparecem por cima dele; as dos outros, só se estiverem na frente.
        val part = pickPart(x, y)?.takeIf { (id, partHit) -> id.first == selected || hit == null || partHit.distance < hit.distance }
        post {
            if (part != null) listener?.onSelect(part.first.first, part.first.second)
            else listener?.onSelect(hit?.objectIndex, null)
        }
    }

    private fun onGlThread(block: () -> Unit) {
        queueEvent {
            block()
            val view = renderer.camera.cameraView
            if (view != reportedView) {
                reportedView = view
                post { listener?.onCameraView(view) }
            }
        }
        requestRender()
    }

    private companion object {
        val TOWER_MESH = ObjectMesh("", SceneRenderer.UNIT_BOX, SceneRenderer.UNIT_BOX_INDICES, ByteArray(0), 0)
        const val ROTATE_DEG_PER_PX = 0.3f
        const val SCALE_PER_PX = 0.004f
    }
}
