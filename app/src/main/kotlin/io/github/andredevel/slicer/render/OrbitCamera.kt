package io.github.andredevel.slicer.render

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
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

    /** Enquadra uma esfera de raio [radius] centrada em [center] de um ângulo padrão. */
    fun frame(center: FloatArray, radius: Float) {
        center.copyInto(target)
        distance = radius / sin(Math.toRadians(FOV_DEG / 2.0)).toFloat()
        yaw = -90f
        pitch = 35f
    }

    fun rotate(dYaw: Float, dPitch: Float) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-89f, 89f)
    }

    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(5f, 5000f)
    }

    /** Desloca o alvo no plano da tela; dx, dy em pixels de uma tela com [heightPx] de altura. */
    fun pan(dx: Float, dy: Float, heightPx: Int) {
        val mmPerPx = 2f * distance * tan(Math.toRadians(FOV_DEG / 2.0)).toFloat() / max(heightPx, 1)
        // Linhas 0 e 1 da matriz de visão são os eixos direita e cima da câmera.
        for (i in 0..2) target[i] += (-dx * view[i * 4] + dy * view[i * 4 + 1]) * mmPerPx
    }

    fun setViewport(width: Int, height: Int) {
        aspect = width.toFloat() / max(height, 1)
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
