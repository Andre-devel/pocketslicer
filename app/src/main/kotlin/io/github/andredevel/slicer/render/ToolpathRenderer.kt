package io.github.andredevel.slicer.render

import android.opengl.GLES30.*
import io.github.andredevel.slicer.core.SlicerEngine
import io.github.andredevel.slicer.core.Toolpaths
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Como colorir os segmentos na pré-visualização. */
enum class ColorMode { FEATURE, SPEED, HEIGHT, WIDTH }

/**
 * O que mostrar dos caminhos: camadas [minLayer]..[maxLayer] (inclusive); na camada de cima,
 * só até o movimento [maxMove] (índice dentro dela). [hiddenRoles]: tipos de extrusão ocultos.
 */
data class PreviewSettings(
    val colorMode: ColorMode = ColorMode.FEATURE,
    val minLayer: Int = 0,
    val maxLayer: Int = Int.MAX_VALUE,
    val maxMove: Int = Int.MAX_VALUE,
    val hiddenRoles: Set<Int> = emptySet(),
    val showTravels: Boolean = false,
)

/** Caminhos do G-code e índices derivados, prontos para desenhar. */
class Preview(val toolpaths: Toolpaths) {
    /** Primeiro segmento de cada camada, mais o total no fim (segmentos vêm em ordem de camada). */
    val layerStart: IntArray = IntArray(toolpaths.layerCount + 1).also { start ->
        var segment = 0
        for (layer in 0..toolpaths.layerCount) {
            while (segment < toolpaths.segmentCount && toolpaths.layers[segment] < layer) segment++
            start[layer] = segment
        }
    }

    /** Movimentos de extrusão da camada [layer]; 0 fora das camadas (G-code sem nenhuma). */
    fun movesInLayer(layer: Int): Int =
        if (layer in 0 until toolpaths.layerCount) layerStart[layer + 1] - layerStart[layer] else 0

    /** Faixa de valores do modo de cor (para legenda e escala), ou null em [ColorMode.FEATURE]. */
    fun range(mode: ColorMode): ClosedFloatingPointRange<Float>? {
        val field = valueField(mode) ?: return null
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until toolpaths.segmentCount) {
            val v = toolpaths.extrusions[i * 9 + field]
            if (v < min) min = v
            if (v > max) max = v
        }
        return if (min <= max) min..max else null
    }

    companion object {
        fun valueField(mode: ColorMode) = when (mode) {
            ColorMode.FEATURE -> null
            ColorMode.WIDTH -> 6
            ColorMode.HEIGHT -> 7
            ColorMode.SPEED -> 8
        }

        /** Cores por tipo de extrusão, as mesmas da GUI do Orca (libvgcode), pelo nome do tipo. */
        private val ROLE_COLORS = mapOf(
            "Undefined" to 0xE6B3B3, "Inner wall" to 0xFFE64D, "Outer wall" to 0xFF7D38,
            "Overhang wall" to 0x1F1FFF, "Sparse infill" to 0xB03029, "Internal solid infill" to 0x9654CC,
            "Top surface" to 0xF04040, "Bottom surface" to 0x665CC7, "Ironing" to 0xFF8C69,
            "Bridge" to 0x4D80BA, "Internal Bridge" to 0x4D80BA, "Gap infill" to 0xFFFFFF,
            "Skirt" to 0x00876E, "Brim" to 0x003B6E, "Support" to 0x00FF00,
            "Support interface" to 0x008000, "Support transition" to 0x004000, "Prime tower" to 0xB3E3AB,
            "Custom" to 0x5ED194, "Multiple" to 0x808080,
        )

        /** Cor RGB (0xRRGGBB) do tipo de extrusão [role]. */
        fun roleColor(role: Int): Int = ROLE_COLORS[SlicerEngine.roleNames.getOrNull(role)] ?: 0x808080

        /** Paleta de faixas da GUI do Orca, do azulado (mínimo) ao avermelhado (máximo). */
        val RANGE_COLORS = intArrayOf(
            0x0B2C7A, 0x135985, 0x1C8891, 0x04D60F, 0xAAF200, 0xFCF903,
            0xF5CE0A, 0xE38820, 0xD16830, 0xC2523C, 0x942616,
        )

        fun rangeColor(t: Float): Int {
            val x = t.coerceIn(0f, 1f) * (RANGE_COLORS.size - 1)
            val i = x.toInt().coerceAtMost(RANGE_COLORS.size - 2)
            val f = x - i
            fun mix(shift: Int) =
                ((RANGE_COLORS[i] shr shift and 0xFF) * (1 - f) + (RANGE_COLORS[i + 1] shr shift and 0xFF) * f).toInt()
            return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }
}

/**
 * Desenha os caminhos como caixas instanciadas (uma por segmento) montadas no vertex shader;
 * camadas, movimentos e tipos visíveis são uniforms, então mexer nas barras não refaz buffers.
 * Só as cores são reenviadas quando muda o modo de cor. Roda na thread GL.
 */
class ToolpathRenderer(private val preview: Preview) {
    private val toolpaths = preview.toolpaths
    private val vao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
    private val travelVao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
    // Caixa unitária, segmento, camada/tipo, cor, índices da caixa, deslocamentos, camada deles.
    private val buffers = IntArray(7).also { glGenBuffers(7, it, 0) }
    private var colorMode: ColorMode? = null

    private val program = SceneRenderer.program(VERTEX, FRAGMENT)
    private val travelProgram = SceneRenderer.program(TRAVEL_VERTEX, SceneRenderer.FLAT_FRAGMENT_SOURCE)

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, buffers[0])
        upload(GL_ARRAY_BUFFER, floatBuffer(BOX_CORNERS))
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)

        glBindBuffer(GL_ARRAY_BUFFER, buffers[1])
        upload(GL_ARRAY_BUFFER, floatBuffer(toolpaths.extrusions))
        // início (3), fim (3), largura e altura (2); a velocidade fica fora.
        for ((location, size, offset) in listOf(Triple(1, 3, 0), Triple(2, 3, 12), Triple(3, 2, 24))) {
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, size, GL_FLOAT, false, 9 * 4, offset)
            glVertexAttribDivisor(location, 1)
        }

        val layerRole = FloatArray(toolpaths.segmentCount * 2) { i ->
            if (i % 2 == 0) toolpaths.layers[i / 2].toFloat() else (toolpaths.roles[i / 2].toInt() and 0xFF).toFloat()
        }
        glBindBuffer(GL_ARRAY_BUFFER, buffers[2])
        upload(GL_ARRAY_BUFFER, floatBuffer(layerRole))
        glEnableVertexAttribArray(4)
        glVertexAttribPointer(4, 2, GL_FLOAT, false, 0, 0)
        glVertexAttribDivisor(4, 1)

        glBindBuffer(GL_ARRAY_BUFFER, buffers[3])
        glEnableVertexAttribArray(5)
        glVertexAttribPointer(5, 4, GL_UNSIGNED_BYTE, true, 0, 0)
        glVertexAttribDivisor(5, 1)

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[4])
        upload(GL_ELEMENT_ARRAY_BUFFER, ByteBuffer.allocateDirect(BOX_INDICES.size * 2).order(ByteOrder.nativeOrder())
            .apply { asShortBuffer().put(BOX_INDICES) })
        glBindVertexArray(0)

        glBindVertexArray(travelVao)
        glBindBuffer(GL_ARRAY_BUFFER, buffers[5])
        upload(GL_ARRAY_BUFFER, floatBuffer(toolpaths.travels))
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
        // Uma camada por vértice (as duas pontas de cada deslocamento).
        val travelLayers = FloatArray(toolpaths.travelLayers.size * 2) { toolpaths.travelLayers[it / 2].toFloat() }
        glBindBuffer(GL_ARRAY_BUFFER, buffers[6])
        upload(GL_ARRAY_BUFFER, floatBuffer(travelLayers))
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 0, 0)
        glBindVertexArray(0)
    }

    fun draw(viewProjection: FloatArray, view: FloatArray, settings: PreviewSettings) {
        if (toolpaths.layerCount == 0) return
        if (settings.colorMode != colorMode) uploadColors(settings.colorMode)
        val maxLayer = settings.maxLayer.coerceIn(0, toolpaths.layerCount - 1)
        val minLayer = settings.minLayer.coerceIn(0, maxLayer)
        val lastSegment = preview.layerStart[maxLayer] + settings.maxMove.coerceIn(0, preview.movesInLayer(maxLayer))

        glUseProgram(program)
        glUniformMatrix4fv(glGetUniformLocation(program, "uViewProjection"), 1, false, viewProjection, 0)
        glUniformMatrix4fv(glGetUniformLocation(program, "uView"), 1, false, view, 0)
        glUniform2f(glGetUniformLocation(program, "uLayers"), minLayer.toFloat(), maxLayer.toFloat())
        glUniform1f(glGetUniformLocation(program, "uLastSegment"), lastSegment.toFloat())
        val visibleRoles = (0 until 31).filter { it !in settings.hiddenRoles }.fold(0) { mask, role -> mask or (1 shl role) }
        glUniform1i(glGetUniformLocation(program, "uRoleMask"), visibleRoles)
        glBindVertexArray(vao)
        glDrawElementsInstanced(GL_TRIANGLES, BOX_INDICES.size, GL_UNSIGNED_SHORT, 0, toolpaths.segmentCount)
        glBindVertexArray(0)

        if (settings.showTravels && toolpaths.travelLayers.isNotEmpty()) {
            glUseProgram(travelProgram)
            glUniformMatrix4fv(glGetUniformLocation(travelProgram, "uMvp"), 1, false, viewProjection, 0)
            glUniform2f(glGetUniformLocation(travelProgram, "uLayers"), minLayer.toFloat(), maxLayer.toFloat())
            glUniform4fv(glGetUniformLocation(travelProgram, "uColor"), 1, TRAVEL_COLOR, 0)
            glBindVertexArray(travelVao)
            glDrawArrays(GL_LINES, 0, toolpaths.travelLayers.size * 2)
            glBindVertexArray(0)
        }
    }

    fun release() {
        glDeleteVertexArrays(2, intArrayOf(vao, travelVao), 0)
        glDeleteBuffers(buffers.size, buffers, 0)
        glDeleteProgram(program)
        glDeleteProgram(travelProgram)
    }

    private fun uploadColors(mode: ColorMode) {
        val n = toolpaths.segmentCount
        val bytes = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        val field = Preview.valueField(mode)
        val range = preview.range(mode)
        for (i in 0 until n) {
            val rgb = if (field == null || range == null) {
                Preview.roleColor(toolpaths.roles[i].toInt() and 0xFF)
            } else {
                val span = range.endInclusive - range.start
                Preview.rangeColor(if (span > 0f) (toolpaths.extrusions[i * 9 + field] - range.start) / span else 0.5f)
            }
            bytes.put((rgb shr 16).toByte()).put((rgb shr 8).toByte()).put(rgb.toByte()).put(-1)
        }
        bytes.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, buffers[3])
        glBufferData(GL_ARRAY_BUFFER, n * 4, bytes, GL_STATIC_DRAW)
        colorMode = mode
    }

    private companion object {
        val TRAVEL_COLOR = floatArrayOf(56 / 255f, 72 / 255f, 155 / 255f, 1f) // cor de deslocamento do Orca

        // Caixa em coordenadas do segmento: x de 0 (início) a 1 (fim), y na largura, z na
        // altura (0 = topo do filete).
        val BOX_CORNERS = floatArrayOf(
            0f, -0.5f, -1f, 1f, -0.5f, -1f, 1f, 0.5f, -1f, 0f, 0.5f, -1f,
            0f, -0.5f, 0f, 1f, -0.5f, 0f, 1f, 0.5f, 0f, 0f, 0.5f, 0f,
        )
        val BOX_INDICES = shortArrayOf(
            0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
            1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7,
        )

        fun floatBuffer(values: FloatArray): ByteBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(values) }

        fun upload(target: Int, data: ByteBuffer) = glBufferData(target, data.capacity(), data, GL_STATIC_DRAW)

        const val VERTEX = """#version 300 es
            layout(location = 0) in vec3 aCorner;
            layout(location = 1) in vec3 aStart;
            layout(location = 2) in vec3 aEnd;
            layout(location = 3) in vec2 aSize;
            layout(location = 4) in vec2 aLayerRole;
            layout(location = 5) in vec4 aColor;
            uniform mat4 uViewProjection;
            uniform mat4 uView;
            uniform vec2 uLayers;
            uniform float uLastSegment;
            uniform int uRoleMask;
            out vec3 vViewPosition;
            out vec3 vColor;
            void main() {
                float layer = aLayerRole.x;
                int role = int(aLayerRole.y + 0.5);
                bool visible = layer >= uLayers.x && layer <= uLayers.y
                    && float(gl_InstanceID) < uLastSegment && ((uRoleMask >> role) & 1) == 1;
                if (!visible) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // fora do volume de recorte
                    return;
                }
                vec3 dir = aEnd - aStart;
                vec2 along = length(dir.xy) > 1e-6 ? normalize(dir.xy) : vec2(1.0, 0.0);
                vec3 side = vec3(-along.y, along.x, 0.0);
                vec3 position = aStart + dir * aCorner.x + side * (aCorner.y * aSize.x) + vec3(0.0, 0.0, aCorner.z * aSize.y);
                vViewPosition = (uView * vec4(position, 1.0)).xyz;
                vColor = aColor.rgb;
                gl_Position = uViewProjection * vec4(position, 1.0);
            }
        """
        const val FRAGMENT = """#version 300 es
            precision highp float;
            in vec3 vViewPosition;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                vec3 normal = normalize(cross(dFdx(vViewPosition), dFdy(vViewPosition)));
                float light = 0.45 + 0.45 * abs(normal.z) + 0.2 * max(normal.y, 0.0);
                fragColor = vec4(vColor * light, 1.0);
            }
        """
        const val TRAVEL_VERTEX = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in float aLayer;
            uniform mat4 uMvp;
            uniform vec2 uLayers;
            void main() {
                gl_Position = aLayer >= uLayers.x && aLayer <= uLayers.y
                    ? uMvp * vec4(aPosition, 1.0) : vec4(2.0, 2.0, 2.0, 1.0);
            }
        """
    }
}
