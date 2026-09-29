package edu.pickupauth

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Rasgos en línea baratos sobre el acelerómetro, para la máquina de estados (no para el modelo):
 *  - varianza de |a| en una ventana deslizante (~0,5 s)
 *  - estimación de gravedad por filtro paso bajo
 */
class MotionTracker(windowSamples: Int) {
    private val mags = FloatArray(windowSamples)
    private var idx = 0
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0
    val gravity = FloatArray(3)
    private var gInit = false

    fun update(x: Float, y: Float, z: Float) {
        val alpha = 0.95f
        if (!gInit) { gravity[0] = x; gravity[1] = y; gravity[2] = z; gInit = true }
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z

        val m = sqrt(x * x + y * y + z * z)
        if (n == mags.size) {
            val old = mags[idx]
            sum -= old; sumSq -= old.toDouble() * old
        } else n++
        mags[idx] = m
        sum += m; sumSq += m.toDouble() * m
        idx = (idx + 1) % mags.size
    }

    fun variance(): Float {
        if (n < 2) return 0f
        val mean = sum / n
        return ((sumSq / n) - mean * mean).coerceAtLeast(0.0).toFloat()
    }

    fun gravitySnapshot() = gravity.copyOf()

    companion object {
        fun angleDeg(a: FloatArray, b: FloatArray): Float {
            val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (na < 1e-3f || nb < 1e-3f) return 0f
            val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(c).toDouble()).toFloat()
        }
    }
}
