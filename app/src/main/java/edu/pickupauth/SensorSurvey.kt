package edu.pickupauth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fase 0: inventario de sensores del dispositivo. Decide qué modo de captura es viable:
 *  - fifoMaxEventCount del acelerómetro / muestras por segundo = segundos que la FIFO puede guardar (modo FIFO_FLUSH)
 *  - existencia de variantes wake-up (modo WAKEUP_BATCH)
 *  - minDelay = frecuencia máxima
 */
object SensorSurvey {

    private val KEY_TYPES = listOf(
        Sensor.TYPE_ACCELEROMETER to "acc", Sensor.TYPE_GYROSCOPE to "gyr",
        Sensor.TYPE_MAGNETIC_FIELD to "mag", Sensor.TYPE_PRESSURE to "prs",
        Sensor.TYPE_LIGHT to "lux", Sensor.TYPE_PROXIMITY to "prox",
        Sensor.TYPE_GRAVITY to "gravity", Sensor.TYPE_GAME_ROTATION_VECTOR to "game_rv",
        Sensor.TYPE_SIGNIFICANT_MOTION to "sig_motion", Sensor.TYPE_STEP_DETECTOR to "step_det"
    )

    fun run(ctx: Context): String {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val all = JSONArray()
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) all.put(describe(s))

        val summary = StringBuilder()
        summary.append("${Build.MANUFACTURER} ${Build.MODEL} · Android SDK ${Build.VERSION.SDK_INT}\n\n")
        val key = JSONObject()
        for ((type, name) in KEY_TYPES) {
            val nw = sm.getDefaultSensor(type, false)
            val w = sm.getDefaultSensor(type, true)
            key.put(name, JSONObject().apply {
                put("non_wakeup", nw?.let { describe(it) } ?: JSONObject.NULL)
                put("wakeup", w?.let { describe(it) } ?: JSONObject.NULL)
            })
            summary.append("$name: ")
            summary.append(if (nw != null) "no-wake ✓ (fifo ${nw.fifoMaxEventCount}, minDelay ${nw.minDelay}µs) " else "no-wake ✗ ")
            summary.append(if (w != null) "wake ✓ (fifo ${w.fifoMaxEventCount})" else "wake ✗")
            summary.append("\n")
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { a ->
            val hz = 1e6 / Config.samplingPeriodUs
            summary.append("\nFIFO del acelerómetro a %.0f Hz ≈ %.1f s de historia (si no la comparte con otros sensores)\n"
                .format(hz, a.fifoMaxEventCount / hz))
        }

        val out = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("key", key).put("all", all)
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sensor_survey.json")
        f.writeText(out.toString(2))
        summary.append("\nGuardado en ${f.absolutePath}")
        return summary.toString()
    }

    private fun describe(s: Sensor) = JSONObject().apply {
        put("name", s.name); put("vendor", s.vendor); put("type", s.stringType)
        put("wakeup", s.isWakeUpSensor); put("reporting_mode", s.reportingMode)
        put("min_delay_us", s.minDelay); put("max_delay_us", s.maxDelay)
        put("fifo_max", s.fifoMaxEventCount); put("fifo_reserved", s.fifoReservedEventCount)
        put("max_range", s.maximumRange.toDouble()); put("resolution", s.resolution.toDouble())
        put("power_ma", s.power.toDouble())
    }
}
