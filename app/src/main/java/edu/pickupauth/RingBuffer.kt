package edu.pickupauth

/** Muestra de un sensor. t = timestamp del sensor en ns (misma base que SystemClock.elapsedRealtimeNanos()). */
data class Sample(val t: Long, val x: Float, val y: Float, val z: Float)

/**
 * Buffer circular de tamaño fijo con arreglos primitivos (sin objetos por muestra, sin presión al GC).
 * Guarda siempre los últimos `capacity` eventos; lo más viejo se sobrescribe.
 * Sensores escalares (luz, proximidad, presión) usan solo x.
 */
class Vec3Ring(private val capacity: Int) {
    private val ts = LongArray(capacity)
    private val v = FloatArray(capacity * 3)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(t: Long, x: Float, y: Float = 0f, z: Float = 0f) {
        ts[head] = t
        val b = head * 3
        v[b] = x; v[b + 1] = y; v[b + 2] = z
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun isEmpty() = size == 0

    /** Copia las muestras con fromNs <= t <= toNs, de la más vieja a la más nueva. */
    @Synchronized
    fun slice(fromNs: Long, toNs: Long): List<Sample> {
        val out = ArrayList<Sample>()
        val start = (head - size + capacity) % capacity
        for (k in 0 until size) {
            val i = (start + k) % capacity
            val t = ts[i]
            if (t in fromNs..toNs) {
                val b = i * 3
                out.add(Sample(t, v[b], v[b + 1], v[b + 2]))
            }
        }
        return out
    }

    /** Última muestra anterior o igual a tNs (para conocer el estado de un sensor on-change al inicio de la ventana). */
    @Synchronized
    fun lastBefore(tNs: Long): Sample? {
        var best: Sample? = null
        val start = (head - size + capacity) % capacity
        for (k in 0 until size) {
            val i = (start + k) % capacity
            if (ts[i] <= tNs) {
                val b = i * 3
                best = Sample(ts[i], v[b], v[b + 1], v[b + 2])
            } else break
        }
        return best
    }
}

/** Registro de eventos discretos (pantalla, desbloqueo, cambios de estado) con el mismo reloj. */
class EventLog(private val capacity: Int = 2000) {
    data class Event(val t: Long, val name: String, val value: String)

    private val buf = ArrayDeque<Event>()

    @Synchronized
    fun add(t: Long, name: String, value: String = "") {
        if (buf.size >= capacity) buf.removeFirst()
        buf.addLast(Event(t, name, value))
    }

    @Synchronized
    fun slice(fromNs: Long, toNs: Long): List<Event> = buf.filter { it.t in fromNs..toNs }
}
