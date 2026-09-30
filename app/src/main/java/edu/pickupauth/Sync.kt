package edu.pickupauth

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Recolección central sin intervención del participante:
 *  1. Corte diario (~00:00): empaqueta en un zip los episodios nuevos, las etiquetas tardías,
 *     stats.csv, lifecycle.csv y un manifest.json. El zip queda en outbox/ como pendiente.
 *  2. Subida: solo con Wi-Fi; si pasan 2 días con pendientes, también con datos móviles.
 *     Reintenta sola; un teléfono apagado sube al volver.
 *  3. Limpieza: borra del teléfono los episodios 3 días después de confirmada su subida.
 * El destino es un Google Apps Script (tools/drive_upload.gs) que guarda el zip en Drive.
 */
object Sync {

    private const val DAY_MS = 24 * 3_600_000L
    const val KEEP_AFTER_UPLOAD_MS = 3 * DAY_MS
    const val MOBILE_AFTER_H = 48L

    fun base(ctx: Context) = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    fun outbox(ctx: Context) = File(base(ctx), "outbox").apply { mkdirs() }
    private fun syncDir(ctx: Context) = File(base(ctx), "sync").apply { mkdirs() }
    fun prefs(ctx: Context) = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun pendingZips(ctx: Context) = outbox(ctx).listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: emptyList()

    // ------------------------------------------------------------------ programación

    fun scheduleDailyCut(ctx: Context) {
        val now = Calendar.getInstance()
        val midnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val req = PeriodicWorkRequestBuilder<CutWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(midnight.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily_cut", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Dos intentos en paralelo: Wi-Fi en cuanto haya, y cualquier red a las 48 h. El que llegue primero sube todo. */
    fun enqueueUpload(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        fun req(net: NetworkType, delayH: Long) = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setInitialDelay(delayH, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("upload_wifi", ExistingWorkPolicy.KEEP, req(NetworkType.UNMETERED, 0))
        wm.enqueueUniqueWork("upload_any", ExistingWorkPolicy.KEEP, req(NetworkType.CONNECTED, MOBILE_AFTER_H))
    }

    /** Botón "Subir ahora" del panel: corte inmediato + subida con cualquier red. */
    fun cutAndUploadNow(ctx: Context) {
        WorkManager.getInstance(ctx)
            .beginUniqueWork("manual", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CutWorker>().build())
            .then(OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
            .enqueue()
    }

    // ------------------------------------------------------------------ corte y empaquetado

    @Synchronized
    fun cut(ctx: Context): File {
        Config.load(ctx)   // el worker puede correr en un proceso recién creado, sin el servicio
        val now = System.currentTimeMillis()
        val lastCut = prefs(ctx).getLong("last_cut_ms", Lifecycle.firstRowMs(ctx) ?: now)
        val root = EpisodeWriter(ctx).root
        val packaged = readTsv(File(syncDir(ctx), "packaged.tsv"))

        // Solo carpetas completas: meta.json se escribe al final y se deja un margen de 30 s.
        val all = root.listFiles { f -> f.isDirectory && File(f, "meta.json").exists() }?.sortedBy { it.name } ?: emptyList()
        val fresh = all.filter { it.name !in packaged && File(it, "meta.json").lastModified() < now - 30_000 }
        val lateLabels = all.filter { d ->
            val at = packaged[d.name] ?: return@filter false
            File(d, "label.json").let { it.exists() && it.lastModified() > at }
        }

        val subject = Config.subjectId
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val name = "${subject}_$stamp.zip"
        val tmp = File(outbox(ctx), "$name.part")
        val byKind = fresh.groupingBy { kindOf(it.name) }.eachCount()
        val manifest = JSONObject().apply {
            put("subject", subject)
            put("impostor_session", Config.impostorSession)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("sdk", Build.VERSION.SDK_INT)
            put("app_version", BuildConfig.VERSION_NAME)
            put("capture_mode", Config.captureMode.name)
            put("capture_enabled", Capture.isEnabled(ctx))
            put("service_running", CaptureService.running)
            put("period_from_ms", lastCut)
            put("created_ms", now)
            put("new_unlock", byKind["unlock"] ?: 0)
            put("new_in_place", byKind["in_place"] ?: 0)
            put("new_false_trigger", byKind["false_trigger"] ?: 0)
            put("new_with_pickup", fresh.count { it.name.startsWith("unlock_") && pickupDetected(it) })
            put("new_labeled", fresh.count { File(it, "label.json").exists() })
            put("late_labels", lateLabels.size)
            put("downtime_h", Lifecycle.downtimeHours(ctx, lastCut, now))
            put("battery_pct", (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("pending_zips", pendingZips(ctx).size)
            put("episodes_on_device", JSONArray(all.map { it.name }))
        }

        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            for (d in fresh) d.listFiles()?.forEach { addFile(zip, it, "episodes/${d.name}/${it.name}") }
            for (d in lateLabels) addFile(zip, File(d, "label.json"), "episodes/${d.name}/label.json")
            for (n in listOf("stats.csv", "lifecycle.csv", "sensor_survey.json")) {
                val f = File(base(ctx), n)
                if (f.exists()) addFile(zip, f, n)
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
        }
        // Lista de episodios del zip: al confirmar la subida pasan a uploaded.tsv.
        File(outbox(ctx), "$name.lst").writeText((fresh + lateLabels).joinToString("\n") { it.name })
        val zipFile = File(outbox(ctx), name)
        tmp.renameTo(zipFile)

        File(syncDir(ctx), "packaged.tsv").appendText(buildString {
            for (d in fresh + lateLabels) append("${d.name}\t$now\n")
        })
        prefs(ctx).edit().putLong("last_cut_ms", now).apply()
        Lifecycle.log(ctx, "cut", "$name;episodes=${fresh.size};late_labels=${lateLabels.size}")
        return zipFile
    }

    private fun kindOf(dir: String) = when {
        dir.startsWith("in_place_") -> "in_place"
        dir.startsWith("false_trigger_") -> "false_trigger"
        else -> "unlock"
    }

    private fun pickupDetected(d: File) = try {
        JSONObject(File(d, "meta.json").readText()).optLong("t_transition_ns", 0L) > 0
    } catch (e: Exception) { false }

    private fun addFile(zip: ZipOutputStream, f: File, entry: String) {
        zip.putNextEntry(ZipEntry(entry))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    // ------------------------------------------------------------------ subida

    /** Sube los pendientes, del más viejo al más nuevo. Devuelve false si alguno falló (se reintenta). */
    @Synchronized
    fun uploadPending(ctx: Context): Boolean {
        Config.load(ctx)
        if (BuildConfig.UPLOAD_URL.isBlank()) {
            prefs(ctx).edit().putString("last_error", "URL de subida no configurada en esta versión").apply()
            return true   // no tiene sentido reintentar: los zips esperan a una versión con URL
        }
        for (zip in pendingZips(ctx)) {
            try {
                post(zip)
            } catch (e: Exception) {
                prefs(ctx).edit()
                    .putString("last_error", "${zip.name}: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200))
                    .putLong("last_error_ms", System.currentTimeMillis()).apply()
                Lifecycle.log(ctx, "upload_failed", "${zip.name};${e.javaClass.simpleName}")
                return false
            }
            val lst = File(outbox(ctx), "${zip.name}.lst")
            val now = System.currentTimeMillis()
            if (lst.exists()) {
                File(syncDir(ctx), "uploaded.tsv").appendText(buildString {
                    lst.readLines().filter { it.isNotBlank() }.forEach { append("$it\t$now\n") }
                })
                lst.delete()
            }
            zip.delete()
            prefs(ctx).edit().putLong("last_upload_ms", now).remove("last_error").apply()
            Lifecycle.log(ctx, "uploaded", zip.name)
        }
        cleanup(ctx)
        return true
    }

    private fun post(zip: File) {
        val manifest = java.util.zip.ZipFile(zip).use { z ->
            z.getInputStream(z.getEntry("manifest.json")).bufferedReader().readText()
        }
        val body = JSONObject().apply {
            put("token", BuildConfig.UPLOAD_TOKEN)
            put("subject", Config.subjectId)
            put("name", zip.name)
            put("manifest", JSONObject(manifest))
            put("zip_b64", Base64.encodeToString(zip.readBytes(), Base64.NO_WRAP))
        }.toString().toByteArray()

        // Apps Script ejecuta el POST y responde con un 302 a la URL donde deja la respuesta.
        val c = URL(BuildConfig.UPLOAD_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.connectTimeout = 30_000; c.readTimeout = 180_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        var code = c.responseCode
        var text = if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw IllegalStateException("302 sin Location")
            c.disconnect()
            val g = URL(loc).openConnection() as HttpURLConnection
            g.connectTimeout = 30_000; g.readTimeout = 60_000
            code = g.responseCode
            (if (code < 400) g.inputStream else g.errorStream)?.bufferedReader()?.readText() ?: ""
        } else {
            (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        }
        if (code >= 400) throw IllegalStateException("HTTP $code")
        val ok = try { JSONObject(text).optBoolean("ok", false) } catch (e: Exception) { false }
        if (!ok) throw IllegalStateException("respuesta: ${text.take(120)}")
    }

    /** Borra del teléfono los episodios subidos hace más de 3 días. */
    fun cleanup(ctx: Context) {
        val uploaded = readTsv(File(syncDir(ctx), "uploaded.tsv"))
        val limit = System.currentTimeMillis() - KEEP_AFTER_UPLOAD_MS
        val root = EpisodeWriter(ctx).root
        var n = 0
        for ((dir, at) in uploaded) if (at < limit) {
            val d = File(root, dir)
            if (d.exists() && d.deleteRecursively()) n++
        }
        if (n > 0) Lifecycle.log(ctx, "cleanup", "deleted=$n")
    }

    /** nombre → último tiempo registrado (ms). */
    private fun readTsv(f: File): Map<String, Long> {
        if (!f.exists()) return emptyMap()
        val m = HashMap<String, Long>()
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 2) p[1].toLongOrNull()?.let { m[p[0]] = it }
        }
        return m
    }
}

class CutWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.cut(applicationContext)
        Sync.cleanup(applicationContext)
        Sync.enqueueUpload(applicationContext)
        return Result.success()
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (Sync.uploadPending(applicationContext)) Result.success() else Result.retry()
}
