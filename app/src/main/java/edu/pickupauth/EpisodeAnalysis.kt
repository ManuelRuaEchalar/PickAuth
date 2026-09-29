package edu.pickupauth

import kotlin.math.sqrt

/**
 * Análisis barato sobre las muestras ya recortadas, al momento de escribir un episodio.
 * No corre en cada muestra: solo una vez por episodio.
 */
object EpisodeAnalysis {

    private const val SEC = 1_000_000_000L

    /**
     * Inicio real del movimiento: retrocede desde `anchorNs` (el disparo de la máquina de estados)
     * en ventanas de 0,3 s hasta encontrar la última ventana quieta (acc y gyr).
     * El disparo por proximidad llega ~1,5–2 s después de que el IMU ya se mueve.
     * Devuelve 0 si no hay tramo quieto en los `maxBackS` segundos previos (p. ej. caminando).
     */
    fun motionOnset(acc: List<Sample>, gyr: List<Sample>, anchorNs: Long, maxBackS: Double = 6.0): Long {
        val w = (Config.onsetWindowMs * 1_000_000L)
        val step = 50_000_000L
        var end = anchorNs
        while (end - w > anchorNs - (maxBackS * SEC).toLong()) {
            if (isQuiet(acc, gyr, end - w, end)) return end
            end -= step
        }
        return 0L
    }

    private fun isQuiet(acc: List<Sample>, gyr: List<Sample>, from: Long, to: Long): Boolean {
        var n = 0; var sum = 0.0; var sumSq = 0.0
        for (s in acc) if (s.t in from until to) {
            val m = mag(s); n++; sum += m; sumSq += m * m
        }
        if (n < 10) return false
        val mean = sum / n
        if (sumSq / n - mean * mean >= Config.onsetAccVar) return false
        var any = false
        for (s in gyr) if (s.t in from until to) {
            any = true
            if (mag(s) >= Config.onsetGyrRadS) return false
        }
        return any
    }

    /**
     * ¿El teléfono se quedó en su sitio durante toda la ventana? (desbloqueado sin levantarlo, p. ej. sobre la mesa)
     * Criterio: en ventanas de 0,5 s, la varianza de |a| nunca supera `motionVar` y la gravedad media
     * no gira más de `inPlaceTiltDeg` respecto a la primera ventana.
     */
    fun stayedInPlace(acc: List<Sample>, fromNs: Long, toNs: Long): Boolean {
        val w = SEC / 2
        var ref: FloatArray? = null
        var t = fromNs
        var windows = 0
        while (t + w <= toNs) {
            var n = 0; var sum = 0.0; var sumSq = 0.0
            val g = FloatArray(3)
            for (s in acc) if (s.t in t until t + w) {
                val m = mag(s); n++; sum += m; sumSq += m * m
                g[0] += s.x; g[1] += s.y; g[2] += s.z
            }
            if (n >= 10) {
                windows++
                val mean = sum / n
                if (sumSq / n - mean * mean > Config.motionVar) return false
                if (ref == null) ref = g
                else if (MotionTracker.angleDeg(ref, g) > Config.inPlaceTiltDeg) return false
            }
            t += w
        }
        return windows > 0
    }

    private fun mag(s: Sample) = sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
}
