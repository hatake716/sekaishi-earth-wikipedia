package io.github.hatake716.sekaishiearth.globe

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * android.opengl.Matrix と同じ列優先 float[16] を扱う純 Kotlin 実装。
 * JVM ユニットテストで Android フレームワークなしにカメラ計算を検証するために用意した。
 */
object Mat4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    /** result = lhs * rhs */
    fun multiply(result: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        val t = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += lhs[k * 4 + r] * rhs[c * 4 + k]
            t[c * 4 + r] = s
        }
        System.arraycopy(t, 0, result, 0, 16)
    }

    fun perspective(m: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(Math.toRadians(fovyDeg.toDouble()) / 2).toFloat()
        val range = 1f / (near - far)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) * range
        m[11] = -1f
        m[14] = 2f * far * near * range
    }

    /** 原点を +Y 上向きで見る、+Z 軸上のカメラ(eyeZ > 0)。 */
    fun lookAtFromZ(m: FloatArray, eyeZ: Float) {
        identity(m)
        m[14] = -eyeZ
    }

    /** m = m * Rx(deg) */
    fun rotateX(m: FloatArray, deg: Double) {
        val c = cos(Math.toRadians(deg)).toFloat()
        val s = sin(Math.toRadians(deg)).toFloat()
        val r = FloatArray(16).also { identity(it) }
        r[5] = c; r[6] = s; r[9] = -s; r[10] = c
        multiply(m, m, r)
    }

    /** m = m * Ry(deg) */
    fun rotateY(m: FloatArray, deg: Double) {
        val c = cos(Math.toRadians(deg)).toFloat()
        val s = sin(Math.toRadians(deg)).toFloat()
        val r = FloatArray(16).also { identity(it) }
        r[0] = c; r[2] = -s; r[8] = s; r[10] = c
        multiply(m, m, r)
    }
}
