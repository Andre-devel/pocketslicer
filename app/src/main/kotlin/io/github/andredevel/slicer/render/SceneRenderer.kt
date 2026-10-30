package io.github.andredevel.slicer.render

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import io.github.andredevel.slicer.core.Bed
import io.github.andredevel.slicer.core.ClipPlane
import io.github.andredevel.slicer.core.ObjectMesh
import io.github.andredevel.slicer.core.PaintMesh
import io.github.andredevel.slicer.core.PaintState
import io.github.andredevel.slicer.core.Part
import io.github.andredevel.slicer.core.Placement
import io.github.andredevel.slicer.core.WipeTower
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * O que o viewport mostra: a mesa da impressora e os objetos sobre ela ([meshes] e
 * [placements] na mesma ordem). Para trocar só posições ou seleção, reutilize a
 * mesma lista [meshes]: as malhas só são reenviadas à GPU quando ela muda.
 * [filamentColors]: RGBA de cada filamento; com mais de um, os objetos são pintados com
 * a cor do filamento de cada triângulo, senão com a cor padrão. [wipeTower]: a torre de purga
 * que o fatiamento vai ter, se houver. [parts]: as peças de cada objeto (as que não são do objeto
 * aparecem translúcidas, por cima dele quando é o selecionado); [selectedPart]: índice de uma
 * delas no objeto [selected]. Como em [meshes], a GPU só recebe [parts] quando a lista muda.
 */
data class Scene(
    val bed: Bed,
    val meshes: List<ObjectMesh> = emptyList(),
    val placements: List<Placement> = emptyList(),
    val selected: Int? = null,
    val filamentColors: List<FloatArray> = emptyList(),
    val wipeTower: WipeTower? = null,
    val parts: List<List<Part>> = emptyList(),
    val selectedPart: Int? = null,
)

/**
 * Pintura do objeto [objectIndex], como o gizmo do Orca a mostra.
 * [highlightAngle] > 0: destaca as faces pendentes além desse ângulo ("Destacar saliências").
 * [gaps]: trechos que o "Preencher lacunas" vai mudar, no estado novo. [clipRatio] > 0: vista em
 * corte; a direção é a da câmera quando ela é ligada ou quando [clipDirection] muda.
 * [colors]: na pintura de cor, RGBA de cada filamento, pelo estado (filamento a partir de 1).
 */
data class PaintOverlay(
    val objectIndex: Int,
    val mesh: PaintMesh,
    val highlightAngle: Float,
    val gaps: PaintMesh? = null,
    val clipRatio: Float = 0f,
    val clipDirection: Int = 0,
    val colors: List<FloatArray>? = null,
)

/**
 * Desenha a cena com OpenGL ES 3. Roda na thread GL do [GLSurfaceView]; [scene] e
 * [camera] só devem ser alterados nela (GLSurfaceView.queueEvent).
 */
/** [dark]: fundo e mesa no tema escuro; senão, no claro. */
class SceneRenderer(var dark: Boolean) : GLSurfaceView.Renderer {
    private val palette get() = if (dark) DARK else LIGHT
    val camera = OrbitCamera()
    var viewportWidth = 1
        private set
    var viewportHeight = 1
        private set

    var scene: Scene? = null
        set(value) {
            val old = field
            field = value
            // Posições novas do motor substituem a transformação provisória do arraste.
            dragTransform = null
            dragPartTransform = null
            dragTower = null
            if (old?.bed !== value?.bed || old?.meshes !== value?.meshes) {
                gpu?.let(::release)
                gpu = null
            }
            if (old?.parts !== value?.parts) {
                partsGpu?.forEach { it.forEach { geometry -> geometry?.release() } }
                partsGpu = null
            }
            val oldBed = old?.bed
            if (value != null && (oldBed == null || !value.bed.shape.contentEquals(oldBed.shape) || value.bed.maxHeight != oldBed.maxHeight))
                frameBed(value.bed)
        }

    /** Transformação provisória do objeto selecionado durante um arraste. */
    var dragTransform: FloatArray? = null

    /** Matriz provisória (na mesa) da peça selecionada durante um arraste. */
    var dragPartTransform: FloatArray? = null

    /** Posição provisória (canto x, y) da torre de purga durante um arraste. */
    var dragTower: FloatArray? = null

    /** Caixa unitária da torre de purga levada à posição, rotação e tamanho dela (e a do arraste). */
    fun towerTransform(): FloatArray? {
        val tower = scene?.wipeTower ?: return null
        val position = dragTower ?: floatArrayOf(tower.x, tower.y)
        return FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, position[0], position[1], 0f)
            Matrix.rotateM(it, 0, tower.rotation, 0f, 0f, 1f)
            Matrix.scaleM(it, 0, tower.width, tower.depth, tower.height.coerceAtLeast(MIN_TOWER_HEIGHT))
        }
    }

    /** Transformações em uso: as da cena, com a [dragTransform] no objeto selecionado. */
    fun transforms(): List<FloatArray> {
        val scene = scene ?: return emptyList()
        return scene.placements.mapIndexed { i, p -> dragTransform?.takeIf { i == scene.selected } ?: p.transform }
    }

    /**
     * Matriz na mesa de cada peça (a do objeto vezes a dela), na ordem de [Scene.parts], com a
     * [dragPartTransform] na selecionada.
     */
    fun partTransforms(): List<List<FloatArray>> {
        val scene = scene ?: return emptyList()
        return transforms().zip(scene.parts).mapIndexed { i, (objectTransform, parts) ->
            parts.mapIndexed { j, part ->
                dragPartTransform?.takeIf { i == scene.selected && j == scene.selectedPart }
                    ?: FloatArray(16).also { Matrix.multiplyMM(it, 0, objectTransform, 0, part.placement.transform, 0) }
            }
        }
    }

    var paint: PaintOverlay? = null
        set(value) {
            if (value?.mesh !== field?.mesh) {
                paintGpu?.geometry?.release()
                paintGpu = null
            }
            if (value?.gaps !== field?.gaps) {
                gapsGpu?.geometry?.release()
                gapsGpu = null
            }
            if (value?.objectIndex != field?.objectIndex || (value?.clipRatio ?: 0f) <= 0f || value?.clipDirection != field?.clipDirection) clipNormal = null
            field = value
        }
    /** Malhas de [paint] e das lacunas, com um trecho por estado (os triângulos vêm agrupados). */
    private var paintGpu: ObjectGeometry? = null
    private var gapsGpu: ObjectGeometry? = null
    /** Normal da vista em corte, fixada ao ligá-la (ObjectClipper::set_position_by_ratio com keep_normal). */
    private var clipNormal: FloatArray? = null

    /**
     * Plano da vista em corte do objeto pintado, como o ObjectClipper do Orca: normal virada para
     * a câmera e offset pela posição de 0 a 1 na esfera da caixa do objeto, em volta da origem dele.
     */
    fun clipPlane(): ClipPlane? {
        val paint = paint?.takeIf { it.clipRatio > 0f } ?: return null
        val placement = scene?.placements?.getOrNull(paint.objectIndex) ?: return null
        camera.update()
        // -dir_forward da câmera: a terceira linha da rotação da vista.
        val normal = clipNormal ?: floatArrayOf(camera.view[2], camera.view[6], camera.view[10]).also { clipNormal = it }
        val center = transforms().getOrNull(paint.objectIndex)?.let { floatArrayOf(it[12], it[13], it[14]) } ?: placement.offset
        val radius = sqrt(placement.size.sumOf { (it * it).toDouble() }).toFloat() / 2
        val dist = normal[0] * center[0] + normal[1] * center[1] + normal[2] * center[2]
        return ClipPlane(normal, dist + radius - paint.clipRatio * 2 * radius)
    }

    /** Caminhos do G-code; com [showPreview], substituem os objetos no desenho. */
    var preview: Preview? = null
        set(value) {
            if (value === field) return
            field = value
            toolpathRenderer?.release()
            toolpathRenderer = null
        }
    var showPreview = false
    var previewSettings = PreviewSettings()
    private var toolpathRenderer: ToolpathRenderer? = null

    /** Recursos GL da [scene] atual; recriados quando o contexto GL é perdido. */
    private var gpu: GpuScene? = null
    /** Malhas de [Scene.parts] (null nas peças do objeto, que já estão na dele). */
    private var partsGpu: List<List<Geometry?>>? = null
    private var flatProgram = 0
    private var meshProgram = 0

    private val mvp = FloatArray(16)
    private val modelView = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Contexto novo: os objetos GL anteriores deixaram de existir.
        gpu = null
        partsGpu = null
        paintGpu = null
        gapsGpu = null
        toolpathRenderer = null
        flatProgram = program(FLAT_VERTEX, FLAT_FRAGMENT_SOURCE)
        meshProgram = program(MESH_VERTEX, MESH_FRAGMENT)
        glEnable(GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        camera.setViewport(width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(unused: GL10?) {
        for (request in thumbnailRequests) request.done(thumbnail(request.width, request.height))
        thumbnailRequests.clear()
        palette.background.let { glClearColor(it[0], it[1], it[2], it[3]) }
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val scene = scene ?: return
        val gpu = gpu ?: upload(scene).also { gpu = it }
        camera.update()

        Matrix.multiplyMM(mvp, 0, camera.projection, 0, camera.view, 0)
        glUseProgram(flatProgram)
        glUniformMatrix4fv(glGetUniformLocation(flatProgram, "uMvp"), 1, false, mvp, 0)
        val color = glGetUniformLocation(flatProgram, "uColor")
        // A superfície fica um pouco atrás das linhas para não disputarem o depth buffer.
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1f, 1f)
        gpu.bedSurface.draw(color, palette.bedSurface)
        glDisable(GL_POLYGON_OFFSET_FILL)
        gpu.bedGrid.draw(color, palette.bedGrid)
        gpu.bedOutline.draw(color, palette.bedOutline)

        val preview = preview
        if (showPreview && preview != null) {
            val renderer = toolpathRenderer ?: ToolpathRenderer(preview).also { toolpathRenderer = it }
            Matrix.multiplyMM(mvp, 0, camera.projection, 0, camera.view, 0)
            renderer.draw(mvp, camera.view, previewSettings)
            return
        }

        drawObjects(gpu, scene, camera, highlight = true)
    }

    /**
     * Pede uma miniatura dos objetos, sem a mesa e com fundo transparente, do ângulo padrão
     * da câmera: RGBA de [width] × [height] com as linhas de baixo para cima (vazia sem objetos).
     * [done] recebe a imagem no próximo quadro, quando o contexto GL certamente existe (um
     * evento da fila do GLSurfaceView pode rodar antes de o contexto ser recriado ao voltar ao app).
     */
    fun requestThumbnail(width: Int, height: Int, done: (ByteArray) -> Unit) {
        thumbnailRequests += ThumbnailRequest(width, height, done)
    }

    private class ThumbnailRequest(val width: Int, val height: Int, val done: (ByteArray) -> Unit)
    private val thumbnailRequests = ArrayList<ThumbnailRequest>()

    private fun thumbnail(width: Int, height: Int): ByteArray {
        val scene = scene?.takeIf { it.meshes.isNotEmpty() } ?: return ByteArray(0)
        val gpu = gpu ?: upload(scene).also { gpu = it }

        // Esfera que envolve os vértices dos objetos na posição atual.
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        val local = floatArrayOf(0f, 0f, 0f, 1f)
        val world = FloatArray(4)
        for ((mesh, transform) in scene.meshes.zip(transforms())) {
            for (v in 0 until mesh.positions.size / 3) {
                mesh.positions.copyInto(local, 0, v * 3, v * 3 + 3)
                Matrix.multiplyMV(world, 0, transform, 0, local, 0)
                for (axis in 0..2) {
                    min[axis] = minOf(min[axis], world[axis])
                    max[axis] = maxOf(max[axis], world[axis])
                }
            }
        }
        val center = FloatArray(3) { (min[it] + max[it]) / 2 }
        val radius = sqrt((0..2).sumOf { ((max[it] - min[it]) / 2).let { h -> h * h }.toDouble() }).toFloat()
        val camera = OrbitCamera().apply {
            setViewport(width, height)
            frame(center, radius.coerceAtLeast(1f))
            update()
        }

        val framebuffer = IntArray(1).also { glGenFramebuffers(1, it, 0) }
        val renderbuffers = IntArray(2).also { glGenRenderbuffers(2, it, 0) }
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0])
        for ((buffer, format, attachment) in listOf(
            Triple(renderbuffers[0], GL_RGBA8, GL_COLOR_ATTACHMENT0),
            Triple(renderbuffers[1], GL_DEPTH_COMPONENT24, GL_DEPTH_ATTACHMENT),
        )) {
            glBindRenderbuffer(GL_RENDERBUFFER, buffer)
            glRenderbufferStorage(GL_RENDERBUFFER, format, width, height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, buffer)
        }
        glViewport(0, 0, width, height)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        drawObjects(gpu, scene, camera, highlight = false)
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteRenderbuffers(2, renderbuffers, 0)
        glDeleteFramebuffers(1, framebuffer, 0)
        glViewport(0, 0, viewportWidth, viewportHeight)
        return ByteArray(width * height * 4).also { pixels.get(it) }
    }

    /** [highlight]: cores de seleção e de fora da mesa; sem ele, todos com a cor normal. */
    private fun drawObjects(gpu: GpuScene, scene: Scene, camera: OrbitCamera, highlight: Boolean) {
        glUseProgram(meshProgram)
        val mvpLocation = glGetUniformLocation(meshProgram, "uMvp")
        val modelViewLocation = glGetUniformLocation(meshProgram, "uModelView")
        val colorLocation = glGetUniformLocation(meshProgram, "uColor")
        val slopeLocation = glGetUniformLocation(meshProgram, "uSlopeNormalZ")
        val clipLocation = glGetUniformLocation(meshProgram, "uClip")
        val modelLocation = glGetUniformLocation(meshProgram, "uModel")
        // Rotação da vista: leva a normal da face, calculada na vista, de volta à mesa.
        val viewRotation = FloatArray(9) { camera.view[it / 3 * 4 + it % 3] }
        glUniformMatrix3fv(glGetUniformLocation(meshProgram, "uViewRotation"), 1, false, viewRotation, 0)
        val paint = paint?.takeIf { highlight && it.objectIndex < scene.meshes.size }
        for ((i, transform) in transforms().withIndex()) {
            Matrix.multiplyMM(modelView, 0, camera.view, 0, transform, 0)
            Matrix.multiplyMM(mvp, 0, camera.projection, 0, modelView, 0)
            glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
            glUniformMatrix4fv(modelViewLocation, 1, false, modelView, 0)
            glUniformMatrix4fv(modelLocation, 1, false, transform, 0)
            val outside = highlight && scene.placements[i].outside
            // Com uma peça selecionada, só ela fica destacada.
            // Pintando, o objeto fica com a cor dele (como no gizmo) e com as saliências destacadas.
            val painting = paint?.objectIndex == i
            val selected = highlight && i == scene.selected && scene.selectedPart == null && !painting
            // GLGizmoPainterBase: normal_z = -cos(ângulo); sem ângulo, nada fica abaixo de -2.
            val clip = if (painting) clipPlane() else null
            glUniform4f(clipLocation, clip?.normal?.get(0) ?: 0f, clip?.normal?.get(1) ?: 0f, clip?.normal?.get(2) ?: 0f, clip?.offset ?: NO_CLIP)
            glUniform1f(slopeLocation, if (painting && paint!!.highlightAngle > 0f) -cos(Math.toRadians(paint.highlightAngle.toDouble())).toFloat() else -2f)
            val colors = scene.filamentColors.takeIf { it.size > 1 }
            for ((filament, first, count) in gpu.objects[i].parts) {
                val filamentColor = colors?.let { it.getOrNull(filament) ?: it.first() }
                val color = when {
                    selected && outside -> SELECTED_OUTSIDE
                    outside -> OUTSIDE
                    // Seleção como um tom laranja sobre a cor do filamento.
                    selected -> filamentColor?.let { mix(it, SELECTED, 0.5f) } ?: SELECTED
                    else -> filamentColor ?: OBJECT
                }
                gpu.objects[i].geometry.draw(colorLocation, color, first, count)
            }
            if (painting) drawPaint(paint!!, colorLocation, slopeLocation)
        }
        glUniform1f(slopeLocation, -2f)
        glUniform4f(clipLocation, 0f, 0f, 0f, NO_CLIP)
        // As peças e a torre não entram na miniatura (como no Orca).
        if (highlight) drawParts(scene, camera, mvpLocation, modelViewLocation, colorLocation)
        val tower = towerTransform()
        if (highlight && tower != null) {
            Matrix.multiplyMM(modelView, 0, camera.view, 0, tower, 0)
            Matrix.multiplyMM(mvp, 0, camera.projection, 0, modelView, 0)
            glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
            glUniformMatrix4fv(modelViewLocation, 1, false, modelView, 0)
            gpu.tower.draw(colorLocation, if (dragTower != null) SELECTED else TOWER)
        }
    }

    /** Triângulos pintados sobre os do objeto (com as matrizes dele já aplicadas), nas cores do Orca. */
    private fun drawPaint(paint: PaintOverlay, colorLocation: Int, slopeLocation: Int) {
        if (paint.mesh.states.isEmpty()) return
        val geometry = paintGpu ?: rangesBy(paint.mesh.indices, paint.mesh.states).let { ObjectGeometry(Geometry(GL_TRIANGLES, paint.mesh.positions, paint.mesh.indices), it) }
            .also { paintGpu = it }
        glUniform1f(slopeLocation, -2f)
        // Na frente das faces do objeto, que estão no mesmo lugar.
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(-1f, -1f)
        for ((state, first, count) in geometry.parts) {
            geometry.geometry.draw(colorLocation, paintColor(paint, state), first, count)
        }
        // As lacunas por cima, translúcidas na cor que vão receber (TriangleSelectorPatch::render).
        val gaps = paint.gaps?.takeIf { it.states.isNotEmpty() }
        if (gaps != null) {
            val gapsGeometry = gapsGpu ?: ObjectGeometry(Geometry(GL_TRIANGLES, gaps.positions, gaps.indices), rangesBy(gaps.indices, gaps.states)).also { gapsGpu = it }
            glPolygonOffset(-2f, -2f)
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            for ((state, first, count) in gapsGeometry.parts) {
                val color = paintColor(paint, state).copyOf().also { it[3] = 0.85f }
                gapsGeometry.geometry.draw(colorLocation, color, first, count)
            }
            glDisable(GL_BLEND)
        }
        glDisable(GL_POLYGON_OFFSET_FILL)
    }

    private fun paintColor(paint: PaintOverlay, state: Int): FloatArray = when {
        paint.colors != null -> paint.colors.getOrNull(state - 1) ?: paint.colors.firstOrNull() ?: OBJECT
        state == PaintState.BLOCK.ordinal -> BLOCKER
        state == PaintState.ENFORCE.ordinal -> ENFORCER
        else -> OBJECT
    }

    /** Peças que não são do objeto, translúcidas, nas cores do Orca; as do selecionado por cima dele. */
    private fun drawParts(scene: Scene, camera: OrbitCamera, mvpLocation: Int, modelViewLocation: Int, colorLocation: Int) {
        val geometries = partsGpu ?: scene.parts.map { parts ->
            parts.map { part -> part.takeIf { it.type != Part.Type.MODEL }?.let { Geometry(GL_TRIANGLES, it.positions, it.indices) } }
        }.also { partsGpu = it }
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        for ((i, transforms) in partTransforms().withIndex()) {
            val selectedObject = i == scene.selected
            if (selectedObject) glDisable(GL_DEPTH_TEST)
            for ((j, transform) in transforms.withIndex()) {
                val geometry = geometries.getOrNull(i)?.getOrNull(j) ?: continue
                Matrix.multiplyMM(modelView, 0, camera.view, 0, transform, 0)
                Matrix.multiplyMM(mvp, 0, camera.projection, 0, modelView, 0)
                glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
                glUniformMatrix4fv(modelViewLocation, 1, false, modelView, 0)
                val color = if (selectedObject && j == scene.selectedPart) SELECTED_PART else PART_COLORS.getValue(scene.parts[i][j].type)
                geometry.draw(colorLocation, color)
            }
            if (selectedObject) glEnable(GL_DEPTH_TEST)
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun frameBed(bed: Bed) {
        val xs = bed.shape.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bed.shape.filterIndexed { i, _ -> i % 2 == 1 }
        if (xs.isEmpty()) return
        val sizeX = xs.max() - xs.min()
        val sizeY = ys.max() - ys.min()
        // A mesa preenche a altura do viewport; objetos altos podem ficar parcialmente fora.
        bedCenter = floatArrayOf((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2, 0f)
        bedRadius = 0.4f * sqrt(sizeX * sizeX + sizeY * sizeY)
        setView(CameraView.HOME)
    }

    // Enquadramento da mesa, calculado em frameBed().
    private var bedCenter = floatArrayOf(0f, 0f, 0f)
    private var bedRadius = 0f

    /** Altura em pixels coberta embaixo do viewport (bottom sheet); setView enquadra acima dela. */
    var bottomInset = 0

    /** Enquadra a mesa pelo ângulo de [view]. */
    fun setView(view: CameraView) {
        if (bedRadius <= 0f) return
        // De frente a mesa vira uma linha: o alvo sobe para ela ficar no terço de baixo.
        val center = if (view == CameraView.FRONT) bedCenter.copyOf().also { it[2] = bedRadius / 2 } else bedCenter
        camera.frame(center, bedRadius, view, if (viewportHeight > 0) bottomInset.toFloat() / viewportHeight else 0f)
    }

    private fun upload(scene: Scene): GpuScene {
        val outline = scene.bed.shape
        val fan = FloatArray(outline.size / 2 * 3) { i -> if (i % 3 == 2) 0f else outline[i / 3 * 2 + i % 3] }
        return GpuScene(
            bedSurface = Geometry(GL_TRIANGLE_FAN, fan),
            bedOutline = Geometry(GL_LINE_LOOP, fan),
            bedGrid = Geometry(GL_LINES, gridLines(outline)),
            objects = scene.meshes.map(::objectGeometry),
            tower = Geometry(GL_TRIANGLES, UNIT_BOX, UNIT_BOX_INDICES),
        )
    }

    /** Um trecho por filamento (os triângulos já vêm agrupados), para desenhar cada um com a cor dele. */
    private fun objectGeometry(mesh: ObjectMesh) =
        ObjectGeometry(Geometry(GL_TRIANGLES, mesh.positions, mesh.indices), rangesBy(mesh.indices, mesh.filaments))

    /** Trechos de triângulos seguidos com o mesmo valor em [values] (um por triângulo). */
    private fun rangesBy(indices: IntArray, values: ByteArray): List<FilamentRange> {
        val triangles = indices.size / 3
        val parts = ArrayList<FilamentRange>()
        var start = 0
        while (start < triangles) {
            val value = values.getOrElse(start) { 0 }
            var end = start + 1
            while (end < triangles && values.getOrElse(end) { 0 } == value) end++
            parts += FilamentRange(value.toInt() and 0xff, start * 3, (end - start) * 3)
            start = end
        }
        return parts
    }

    private fun release(gpu: GpuScene) {
        (listOf(gpu.bedSurface, gpu.bedOutline, gpu.bedGrid, gpu.tower) + gpu.objects.map { it.geometry }).forEach(Geometry::release)
    }

    private class GpuScene(
        val bedSurface: Geometry,
        val bedOutline: Geometry,
        val bedGrid: Geometry,
        val objects: List<ObjectGeometry>,
        val tower: Geometry,
    )

    /** [count] índices a partir de [first], todos do [filament] (ou do estado da pintura). */
    private data class FilamentRange(val filament: Int, val first: Int, val count: Int)

    private class ObjectGeometry(val geometry: Geometry, val parts: List<FilamentRange>)

    /** VAO com posições (x, y, z) e, opcionalmente, índices de 32 bits. */
    private class Geometry(private val mode: Int, positions: FloatArray, indices: IntArray? = null) {
        private val vao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
        private val buffers = IntArray(2).also { glGenBuffers(2, it, 0) }
        private val count = indices?.size ?: (positions.size / 3)
        private val indexed = indices != null

        init {
            glBindVertexArray(vao)
            glBindBuffer(GL_ARRAY_BUFFER, buffers[0])
            glBufferData(GL_ARRAY_BUFFER, positions.size * 4, buffer(positions.size * 4).asFloatBuffer().put(positions).flip(), GL_STATIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            if (indices != null) {
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[1])
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, buffer(indices.size * 4).asIntBuffer().put(indices).flip(), GL_STATIC_DRAW)
            }
            glBindVertexArray(0)
        }

        /** Desenha [count] elementos a partir de [first] (padrão: todos). */
        fun draw(colorLocation: Int, color: FloatArray, first: Int = 0, count: Int = this.count) {
            glUniform4fv(colorLocation, 1, color, 0)
            glBindVertexArray(vao)
            if (indexed) glDrawElements(mode, count, GL_UNSIGNED_INT, first * 4) else glDrawArrays(mode, first, count)
            glBindVertexArray(0)
        }

        fun release() {
            glDeleteVertexArrays(1, intArrayOf(vao), 0)
            glDeleteBuffers(2, buffers, 0)
        }

        private fun buffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    /** Cores RGBA do que não é objeto nem caminho do G-code. */
    private class Palette(val background: FloatArray, val bedSurface: FloatArray, val bedGrid: FloatArray, val bedOutline: FloatArray)

    companion object {
        private const val GRID_STEP = 10f

        // Cores do tema do app (ui/theme): fundo e mesa neutros para o modelo se destacar.
        private val DARK = Palette(
            background = floatArrayOf(0.090f, 0.094f, 0.106f, 1f),
            bedSurface = floatArrayOf(0.141f, 0.153f, 0.169f, 1f),
            bedGrid = floatArrayOf(0.200f, 0.216f, 0.235f, 1f),
            bedOutline = floatArrayOf(0.290f, 0.306f, 0.333f, 1f),
        )
        private val LIGHT = Palette(
            background = floatArrayOf(0.937f, 0.929f, 0.914f, 1f),
            bedSurface = floatArrayOf(0.886f, 0.875f, 0.851f, 1f),
            bedGrid = floatArrayOf(0.820f, 0.804f, 0.773f, 1f),
            bedOutline = floatArrayOf(0.780f, 0.761f, 0.725f, 1f),
        )
        private val OBJECT = floatArrayOf(0.68f, 0.71f, 0.73f, 1f)
        // Verde da torre de purga na legenda da pré-visualização (cor do Orca).
        private val TOWER = floatArrayOf(0.70f, 0.89f, 0.67f, 1f)
        // Com objetos de altura zero a caixa ainda aparece.
        private const val MIN_TOWER_HEIGHT = 0.2f

        /** Caixa de 0 a 1 nos três eixos (vértices e triângulos). */
        val UNIT_BOX = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
        val UNIT_BOX_INDICES = intArrayOf(
            0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
            1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7,
        )
        private val SELECTED = floatArrayOf(1.00f, 0.54f, 0.24f, 1f)
        private val SELECTED_PART = floatArrayOf(1.00f, 0.54f, 0.24f, 0.6f)
        // GLVolume::MODEL_NEGTIVE_COL, MODEL_MIDIFIER_COL, SUPPORT_BLOCKER_COL e SUPPORT_ENFORCER_COL.
        private val PART_COLORS = mapOf(
            Part.Type.NEGATIVE to floatArrayOf(0.3f, 0.3f, 0.3f, 0.4f),
            Part.Type.MODIFIER to floatArrayOf(1.0f, 1.0f, 0.0f, 0.6f),
            Part.Type.SUPPORT_BLOCKER to floatArrayOf(1.0f, 0.3f, 0.3f, 0.4f),
            Part.Type.SUPPORT_ENFORCER to floatArrayOf(0.3f, 0.3f, 1.0f, 0.4f),
        )
        // TriangleSelectorGUI::enforcers_color e blockers_color.
        private val ENFORCER = floatArrayOf(0.5f, 1.0f, 0.5f, 1f)
        private val BLOCKER = floatArrayOf(1.0f, 0.5f, 0.5f, 1f)
        /** Offset que não esconde nada (o ClipsNothing do Orca). */
        private const val NO_CLIP = 1e30f
        private val OUTSIDE = floatArrayOf(1.00f, 0.42f, 0.37f, 1f)
        private val SELECTED_OUTSIDE = floatArrayOf(0.98f, 0.40f, 0.35f, 1f)

        private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(4) { a[it] + (b[it] - a[it]) * t }

        /** Linhas a cada [GRID_STEP] mm recortadas pelo contorno da mesa (que pode ser circular). */
        private fun gridLines(outline: FloatArray): FloatArray {
            val n = outline.size / 2
            if (n < 3) return FloatArray(0)
            val out = ArrayList<Float>()
            for (axis in 0..1) {
                val other = 1 - axis
                val values = (0 until n).map { outline[it * 2 + axis] }
                var c = ceil(values.min() / GRID_STEP) * GRID_STEP
                while (c <= floor(values.max() / GRID_STEP) * GRID_STEP) {
                    // Cruzamentos da reta (eixo = c) com as arestas, pareados de fora para dentro.
                    val hits = (0 until n).mapNotNull { i ->
                        val a0 = outline[i * 2 + axis]
                        val a1 = outline[(i + 1) % n * 2 + axis]
                        if ((a0 <= c) == (a1 <= c)) return@mapNotNull null
                        val t = (c - a0) / (a1 - a0)
                        outline[i * 2 + other] + t * (outline[(i + 1) % n * 2 + other] - outline[i * 2 + other])
                    }.sorted()
                    for (k in 0 until hits.size / 2 * 2 step 2) {
                        for (h in listOf(hits[k], hits[k + 1])) {
                            val p = FloatArray(3)
                            p[axis] = c
                            p[other] = h
                            out += p.asList()
                        }
                    }
                    c += GRID_STEP
                }
            }
            return out.toFloatArray()
        }

        fun program(vertex: String, fragment: String): Int {
            val program = glCreateProgram()
            for ((type, source) in listOf(GL_VERTEX_SHADER to vertex, GL_FRAGMENT_SHADER to fragment)) {
                val shader = glCreateShader(type)
                glShaderSource(shader, source)
                glCompileShader(shader)
                val ok = IntArray(1).also { glGetShaderiv(shader, GL_COMPILE_STATUS, it, 0) }[0]
                check(ok != 0) { "shader: " + glGetShaderInfoLog(shader) }
                glAttachShader(program, shader)
                glDeleteShader(shader)
            }
            glLinkProgram(program)
            val ok = IntArray(1).also { glGetProgramiv(program, GL_LINK_STATUS, it, 0) }[0]
            check(ok != 0) { "program: " + glGetProgramInfoLog(program) }
            return program
        }

        private const val FLAT_VERTEX = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }
        """
        /** Cor única ([uColor]); também usado pelos deslocamentos do [ToolpathRenderer]. */
        const val FLAT_FRAGMENT_SOURCE = """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """
        private const val MESH_VERTEX = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uMvp;
            uniform mat4 uModelView;
            uniform mat4 uModel;
            out vec3 vViewPosition;
            out vec3 vWorldPosition;
            void main() {
                vViewPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
                vWorldPosition = (uModel * vec4(aPosition, 1.0)).xyz;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        // Normal da face pelas derivadas da posição: dispensa um atributo de normal
        // e permite usar a malha indexada como vem do libslic3r.
        private const val MESH_FRAGMENT = """#version 300 es
            precision highp float;
            in vec3 vViewPosition;
            in vec3 vWorldPosition;
            uniform vec4 uColor;
            uniform mat3 uViewRotation;
            // Vista em corte: some o lado com normal·p > offset.
            uniform vec4 uClip;
            // Faces com a normal (na mesa) abaixo disto ficam avermelhadas, como as saliências no
            // gizmo de pintura de suporte do Orca (shader gouraud, slope).
            uniform float uSlopeNormalZ;
            out vec4 fragColor;
            void main() {
                if (dot(uClip.xyz, vWorldPosition) > uClip.w) discard;
                vec3 normal = normalize(cross(dFdx(vViewPosition), dFdy(vViewPosition)));
                // Luz na direção da câmera, mais uma vinda de cima para dar volume.
                float light = 0.35 + 0.5 * abs(normal.z) + 0.25 * max(normal.y, 0.0);
                vec3 color = uColor.rgb;
                // A face vista está virada para a câmera; transposta da rotação = inversa.
                vec3 facing = dot(normal, vViewPosition) > 0.0 ? -normal : normal;
                if ((facing * uViewRotation).z < uSlopeNormalZ - 0.0001) color = color * 0.5 + vec3(0.78, 0.0, 0.0) * 0.5;
                fragColor = vec4(color * light, uColor.a);
            }
        """
    }
}
