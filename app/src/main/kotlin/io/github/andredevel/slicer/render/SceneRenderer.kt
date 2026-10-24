package io.github.andredevel.slicer.render

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import io.github.andredevel.slicer.core.Bed
import io.github.andredevel.slicer.core.ObjectMesh
import io.github.andredevel.slicer.core.Placement
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * O que o viewport mostra: a mesa da impressora e os objetos sobre ela ([meshes] e
 * [placements] na mesma ordem). Para trocar só posições ou seleção, reutilize a
 * mesma lista [meshes]: as malhas só são reenviadas à GPU quando ela muda.
 */
data class Scene(
    val bed: Bed,
    val meshes: List<ObjectMesh> = emptyList(),
    val placements: List<Placement> = emptyList(),
    val selected: Int? = null,
)

/**
 * Desenha a cena com OpenGL ES 3. Roda na thread GL do [GLSurfaceView]; [scene] e
 * [camera] só devem ser alterados nela (GLSurfaceView.queueEvent).
 */
/** [dark]: fundo e mesa no tema escuro; senão, no claro. */
class SceneRenderer(dark: Boolean) : GLSurfaceView.Renderer {
    private val palette = if (dark) DARK else LIGHT
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
            if (old?.bed !== value?.bed || old?.meshes !== value?.meshes) {
                gpu?.let(::release)
                gpu = null
            }
            val oldBed = old?.bed
            if (value != null && (oldBed == null || !value.bed.shape.contentEquals(oldBed.shape) || value.bed.maxHeight != oldBed.maxHeight))
                frameBed(value.bed)
        }

    /** Transformação provisória do objeto selecionado durante um arraste. */
    var dragTransform: FloatArray? = null

    /** Transformações em uso: as da cena, com a [dragTransform] no objeto selecionado. */
    fun transforms(): List<FloatArray> {
        val scene = scene ?: return emptyList()
        return scene.placements.mapIndexed { i, p -> dragTransform?.takeIf { i == scene.selected } ?: p.transform }
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
    private var flatProgram = 0
    private var meshProgram = 0

    private val mvp = FloatArray(16)
    private val modelView = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Contexto novo: os objetos GL anteriores deixaram de existir.
        gpu = null
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
        for ((i, transform) in transforms().withIndex()) {
            Matrix.multiplyMM(modelView, 0, camera.view, 0, transform, 0)
            Matrix.multiplyMM(mvp, 0, camera.projection, 0, modelView, 0)
            glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
            glUniformMatrix4fv(modelViewLocation, 1, false, modelView, 0)
            val outside = highlight && scene.placements[i].outside
            val color = when {
                highlight && i == scene.selected -> if (outside) SELECTED_OUTSIDE else SELECTED
                outside -> OUTSIDE
                else -> OBJECT
            }
            gpu.objects[i].draw(colorLocation, color)
        }
    }

    private fun frameBed(bed: Bed) {
        val xs = bed.shape.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = bed.shape.filterIndexed { i, _ -> i % 2 == 1 }
        if (xs.isEmpty()) return
        val sizeX = xs.max() - xs.min()
        val sizeY = ys.max() - ys.min()
        // A mesa preenche a altura do viewport; objetos altos podem ficar parcialmente fora.
        val center = floatArrayOf((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2, 0f)
        camera.frame(center, 0.4f * sqrt(sizeX * sizeX + sizeY * sizeY))
    }

    private fun upload(scene: Scene): GpuScene {
        val outline = scene.bed.shape
        val fan = FloatArray(outline.size / 2 * 3) { i -> if (i % 3 == 2) 0f else outline[i / 3 * 2 + i % 3] }
        return GpuScene(
            bedSurface = Geometry(GL_TRIANGLE_FAN, fan),
            bedOutline = Geometry(GL_LINE_LOOP, fan),
            bedGrid = Geometry(GL_LINES, gridLines(outline)),
            objects = scene.meshes.map { Geometry(GL_TRIANGLES, it.positions, it.indices) },
        )
    }

    private fun release(gpu: GpuScene) {
        (listOf(gpu.bedSurface, gpu.bedOutline, gpu.bedGrid) + gpu.objects).forEach(Geometry::release)
    }

    private class GpuScene(
        val bedSurface: Geometry,
        val bedOutline: Geometry,
        val bedGrid: Geometry,
        val objects: List<Geometry>,
    )

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

        fun draw(colorLocation: Int, color: FloatArray) {
            glUniform4fv(colorLocation, 1, color, 0)
            glBindVertexArray(vao)
            if (indexed) glDrawElements(mode, count, GL_UNSIGNED_INT, 0) else glDrawArrays(mode, 0, count)
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

        private val DARK = Palette(
            background = floatArrayOf(0.16f, 0.17f, 0.19f, 1f),
            bedSurface = floatArrayOf(0.30f, 0.32f, 0.35f, 1f),
            bedGrid = floatArrayOf(0.40f, 0.42f, 0.46f, 1f),
            bedOutline = floatArrayOf(0.75f, 0.77f, 0.80f, 1f),
        )
        private val LIGHT = Palette(
            background = floatArrayOf(0.93f, 0.94f, 0.95f, 1f),
            bedSurface = floatArrayOf(0.78f, 0.80f, 0.83f, 1f),
            bedGrid = floatArrayOf(0.66f, 0.68f, 0.72f, 1f),
            bedOutline = floatArrayOf(0.30f, 0.32f, 0.36f, 1f),
        )
        private val OBJECT = floatArrayOf(0.20f, 0.68f, 0.38f, 1f)
        private val SELECTED = floatArrayOf(0.96f, 0.58f, 0.16f, 1f)
        private val OUTSIDE = floatArrayOf(0.80f, 0.18f, 0.18f, 1f)
        private val SELECTED_OUTSIDE = floatArrayOf(0.98f, 0.40f, 0.35f, 1f)

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
            out vec3 vViewPosition;
            void main() {
                vViewPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        // Normal da face pelas derivadas da posição: dispensa um atributo de normal
        // e permite usar a malha indexada como vem do libslic3r.
        private const val MESH_FRAGMENT = """#version 300 es
            precision highp float;
            in vec3 vViewPosition;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                vec3 normal = normalize(cross(dFdx(vViewPosition), dFdy(vViewPosition)));
                // Luz na direção da câmera, mais uma vinda de cima para dar volume.
                float light = 0.35 + 0.5 * abs(normal.z) + 0.25 * max(normal.y, 0.0);
                fragColor = vec4(uColor.rgb * light, uColor.a);
            }
        """
    }
}
