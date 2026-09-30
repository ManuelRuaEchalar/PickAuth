package edu.pickupauth

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Punto único para arrancar y detener la captura. Recuerda si el investigador la dejó activa
 * ("capture_enabled"), para que el reinicio, la actualización de la app y el vigilante la restauren solos.
 */
object Capture {

    private const val PREFS = "cfg"
    private const val KEY_ENABLED = "capture_enabled"
    const val KEY_AUTOSTART = "autostart"

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun autostart(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTOSTART, true)

    /** reason: user | boot | update | watchdog. */
    fun start(ctx: Context, reason: String) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, true).apply()
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, CaptureService::class.java).putExtra(CaptureService.EXTRA_REASON, reason))
        scheduleBackground(ctx)
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, false).apply()
        Lifecycle.log(ctx, "capture_stopped_by_user")
        ctx.stopService(Intent(ctx, CaptureService::class.java))
    }

    /** Vigilante y subida diaria. KEEP: volver a llamarlo no reinicia los temporizadores. */
    fun scheduleBackground(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build())
        Sync.scheduleDailyCut(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Si el fabricante mató el servicio, lo relanza. Android lo permite porque la app está exenta de optimización de batería. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Capture.isEnabled(ctx) && !CaptureService.running) {
            Lifecycle.log(ctx, "watchdog_restart")
            try {
                Capture.start(ctx, "watchdog")
            } catch (e: Exception) {
                // p. ej. ForegroundServiceStartNotAllowedException si se perdió la exención de batería.
                Lifecycle.log(ctx, "watchdog_failed", e.javaClass.simpleName)
            }
        }
        return Result.success()
    }
}

/**
 * Registro de vida del servicio: arranques, paradas y un latido cada 10 min.
 * Los huecos entre filas son el tiempo en que la captura estuvo caída (teléfono apagado, app cerrada…).
 * Columnas: wall_ms, elapsed_ns, event, detail.
 */
object Lifecycle {
    fun file(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "lifecycle.csv")

    @Synchronized
    fun log(ctx: Context, event: String, detail: String = "") {
        val f = file(ctx)
        val header = !f.exists()
        f.appendText(buildString {
            if (header) append("wall_ms,elapsed_ns,event,detail\n")
            append("${System.currentTimeMillis()},${SystemClock.elapsedRealtimeNanos()},$event,${detail.replace(',', ';')}\n")
        })
    }

    fun firstRowMs(ctx: Context): Long? {
        val f = file(ctx)
        if (!f.exists()) return null
        return f.bufferedReader().useLines { l -> l.drop(1).firstOrNull()?.substringBefore(',')?.toLongOrNull() }
    }

    /** Horas sin latido entre fromMs y toMs (huecos mayores a 20 min entre filas consecutivas). */
    fun downtimeHours(ctx: Context, fromMs: Long, toMs: Long): Double {
        val f = file(ctx)
        if (!f.exists()) return (toMs - fromMs) / 3.6e6
        val times = f.readLines().drop(1).mapNotNull { it.substringBefore(',').toLongOrNull() }
            .filter { it in fromMs..toMs }.sorted()
        val points = listOf(fromMs) + times + listOf(toMs)
        var gapMs = 0L
        for (i in 1 until points.size) {
            val d = points[i] - points[i - 1]
            if (d > 20 * 60_000L) gapMs += d
        }
        return gapMs / 3.6e6
    }
}
