package edu.pickupauth

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escribe un episodio como carpeta con un CSV por sensor + events.csv + meta.json.
 * Ubicación: /sdcard/Android/data/edu.pickupauth/files/episodes/  (se extrae con `adb pull`).
 */
class EpisodeWriter(private val ctx: Context) {

    val root: File by lazy {
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "episodes").apply { mkdirs() }
    }

    fun write(
        kind: String,                         // "unlock" | "false_trigger"
        fromNs: Long, toNs: Long,
        streams: Map<String, Vec3Ring>,
        events: List<EventLog.Event>,
        meta: JSONObject
    ): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val dir = File(root, "${kind}_$stamp").apply { mkdirs() }

        val coverage = JSONObject()
        for ((name, ring) in streams) {
            val samples = ring.slice(fromNs, toNs)
            File(dir, "$name.csv").bufferedWriter().use { w ->
                w.write("t_ns,x,y,z\n")
                // Para sensores on-change, se agrega el último valor previo a la ventana.
                if (name in ON_CHANGE) ring.lastBefore(fromNs)?.let { w.write("${it.t},${it.x},${it.y},${it.z}\n") }
                for (s in samples) w.write("${s.t},${s.x},${s.y},${s.z}\n")
            }
            coverage.put(name, JSONObject().apply {
                put("n", samples.size)
                put("first_ns", samples.firstOrNull()?.t ?: 0L)
                put("last_ns", samples.lastOrNull()?.t ?: 0L)
                put("max_gap_ms", maxGapMs(samples))
            })
        }

        File(dir, "events.csv").bufferedWriter().use { w ->
            w.write("t_ns,name,value\n")
            for (e in events) w.write("${e.t},${e.name},${e.value.replace(',', ';')}\n")
        }

        meta.put("kind", kind)
        meta.put("window_from_ns", fromNs)
        meta.put("window_to_ns", toNs)
        meta.put("coverage", coverage)
        meta.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT); put("fingerprint", Build.FINGERPRINT)
        })
        meta.put("config", JSONObject().apply {
            put("mode", Config.captureMode.name)
            put("sampling_period_us", Config.samplingPeriodUs)
            put("pre_s", Config.preUnlockSeconds); put("post_s", Config.postUnlockSeconds)
        })
        meta.put("subject", Config.subjectId)
        meta.put("impostor_session", Config.impostorSession)
        File(dir, "meta.json").writeText(meta.toString(2))
        return dir
    }

    /** Etiqueta dada por el usuario desde la notificación (muestreo de experiencia). */
    fun writeLabel(dirName: String, label: String) {
        val dir = File(root, dirName)
        if (!dir.exists()) return
        File(dir, "label.json").writeText(JSONObject().apply {
            put("origin_label", label)
            put("labeled_at_ms", System.currentTimeMillis())
        }.toString(2))
    }

    private fun maxGapMs(s: List<Sample>): Double {
        var m = 0L
        for (i in 1 until s.size) m = maxOf(m, s[i].t - s[i - 1].t)
        return m / 1e6
    }

    companion object {
        val ON_CHANGE = setOf("lux", "prox")
    }
}
