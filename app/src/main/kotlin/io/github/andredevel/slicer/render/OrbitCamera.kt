package io.github.andredevel.slicer.render

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Câmera orbital em torno de [target], com Z para cima como no Orca. Ângulos em graus. */
class OrbitCamera {
    val target = floatArrayOf(0f, 0f, 0f)
    var distance = 500f
    var yaw = -90f
    var pitch = 35f

    val view = FloatArray(16)
    val projection = FloatArray(16)
    private var aspect = 1f

    // Raio enquadrado por frame(); null depois que o usuário mexe na câmera.
    private var framedRadius: Float? = null

    /**
     * Enquadra uma esfera de raio [radius] centrada em [center] de um ângulo padrão. Até o
     * usuário mexer na câmera, o enquadramento acompanha mudanças de proporção do viewport.
     */
    fun frame(center: FloatArray, radius: Float) {
        center.copyInto(target)
        framedRadius = radius
        yaw = -90f
        pitch = 35f
        fitFramedRadius()
    }

    fun rotate(dYaw: Float, dPitch: Float) {
        framedRadius = null
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-89f, 89f)
    }

    fun zoom(factor: Float) {
        framedRadius = null
        distance = (distance / factor).coerceIn(5f, 5000f)
    }

    /** Desloca o alvo no plano da tela; dx, dy em pixels de uma tela com [heightPx] de altura. */
    fun pan(dx: Float, dy: Float, heightPx: Int) {
        framedRadius = null
        val mmPerPx = 2f * distance * tan(Math.toRadians(FOV_DEG / 2.0)).toFloat() / max(heightPx, 1)
        // Linhas 0 e 1 da matriz de visão são os eixos direita e cima da câmera.
        for (i in 0..2) target[i] += (-dx * view[i * 4] + dy * view[i * 4 + 1]) * mmPerPx
    }

    fun setViewport(width: Int, height: Int) {
        aspect = width.toFloat() / max(height, 1)
        fitFramedRadius()
    }

    /** Distância em que a esfera enquadrada cabe no menor dos campos de visão (retrato: o horizontal). */
    private fun fitFramedRadius() {
        val radius = framedRadius ?: return
        val halfVertical = Math.toRadians(FOV_DEG / 2.0)
        val halfHorizontal = atan(tan(halfVertical) * aspect)
        distance = radius / sin(min(halfVertical, halfHorizontal)).toFloat()
    }

    fun update() {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val eyeX = target[0] + distance * (cos(pitchRad) * cos(yawRad)).toFloat()
        val eyeY = target[1] + distance * (cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeZ = target[2] + distance * sin(pitchRad).toFloat()
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, target[0], target[1], target[2], 0f, 0f, 1f)
        Matrix.perspectiveM(projection, 0, FOV_DEG.toFloat(), aspect, distance * 0.01f, distance * 10f)
    }

    private companion object {
        const val FOV_DEG = 35.0
    }
}
