package io.github.andredevel.slicer.render

import android.opengl.Matrix
import io.github.andredevel.slicer.core.Triangles

/** Raio em coordenadas da mesa. */
class Ray(val origin: FloatArray, val direction: FloatArray) {
    fun at(t: Float) = FloatArray(3) { origin[it] + t * direction[it] }

    /** Ponto em que o raio cruza o plano horizontal z = [z], ou null se paralelo/atrás. */
    fun intersectZ(z: Float): FloatArray? {
        if (direction[2] == 0f) return null
        val t = (z - origin[2]) / direction[2]
        return if (t > 0f) at(t) else null
    }
}

/** Triângulo [triangle] da malha [objectIndex] (índice na lista dada a [pick]) atingido em [point], a [distance] da origem do raio. */
class Hit(val objectIndex: Int, val triangle: Int, val point: FloatArray, val distance: Float)

/** Raio que sai da câmera pelo pixel (x, y) de um viewport [width] x [height]. */
fun OrbitCamera.ray(x: Float, y: Float, width: Int, height: Int): Ray {
    update()
    val viewProjection = FloatArray(16)
    val inverse = FloatArray(16)
    Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    Matrix.invertM(inverse, 0, viewProjection, 0)
    val ndcX = 2f * x / width - 1f
    val ndcY = 1f - 2f * y / height
    fun unproject(ndcZ: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(ndcX, ndcY, ndcZ, 1f), 0)
        return FloatArray(3) { out[it] / out[3] }
    }
    val near = unproject(-1f)
    val far = unproject(1f)
    return Ray(near, FloatArray(3) { far[it] - near[it] })
}

/**
 * Triângulo mais próximo atingido pelo [ray] entre os objetos (malha + transformação),
 * por força bruta (Möller–Trumbore) — suficiente para um toque.
 */
fun pick(ray: Ray, meshes: List<Triangles>, transforms: List<FloatArray>): Hit? {
    var best: Hit? = null
    var bestT = Float.MAX_VALUE
    val inverse = FloatArray(16)
    val origin = FloatArray(4)
    val direction = FloatArray(4)
    for ((index, mesh) in meshes.withIndex()) {
        // Testa no espaço local do objeto: o parâmetro t do raio não muda.
        if (!Matrix.invertM(inverse, 0, transforms[index], 0)) continue
        Matrix.multiplyMV(origin, 0, inverse, 0, floatArrayOf(ray.origin[0], ray.origin[1], ray.origin[2], 1f), 0)
        Matrix.multiplyMV(direction, 0, inverse, 0, floatArrayOf(ray.direction[0], ray.direction[1], ray.direction[2], 0f), 0)
        val p = mesh.positions
        val idx = mesh.indices
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3] * 3
            val b = idx[t * 3 + 1] * 3
            val c = idx[t * 3 + 2] * 3
            val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
            val px = direction[1] * e2z - direction[2] * e2y
            val py = direction[2] * e2x - direction[0] * e2z
            val pz = direction[0] * e2y - direction[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (det > -1e-12f && det < 1e-12f) continue
            val inv = 1f / det
            val sx = origin[0] - p[a]; val sy = origin[1] - p[a + 1]; val sz = origin[2] - p[a + 2]
            val u = (sx * px + sy * py + sz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = (direction[0] * qx + direction[1] * qy + direction[2] * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > 0f && dist < bestT) {
                bestT = dist
                best = Hit(index, t, ray.at(dist), dist)
            }
        }
    }
    return best
}
