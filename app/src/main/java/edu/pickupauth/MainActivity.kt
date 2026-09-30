package edu.pickupauth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel de control del investigador: lista de verificación en colores, sujeto/sesión de impostor,
 * modo de captura, permisos, subida de datos y estado en vivo.
 */
class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var checklist: TextView
    private lateinit var live: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            checklist.text = checklistText()
            live.text = "Servicio: ${CaptureService.status}"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 64, 40, 40) }
        out = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }

        fun button(label: String, action: () -> Unit) =
            col.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        col.addView(TextView(this).apply { text = "PickupAuth · ${BuildConfig.VERSION_NAME}"; textSize = 20f })
        checklist = TextView(this).apply { textSize = 14f; setPadding(0, 16, 0, 8) }
        live = TextView(this).apply { textSize = 13f; setPadding(0, 8, 0, 16) }
        col.addView(checklist); col.addView(live)

        // --- Sujeto y sesión ---
        val subject = EditText(this).apply { hint = "ID de sujeto (p. ej. S01)"; setText(Config.subjectId) }
        val impostor = CheckBox(this).apply { text = "Sesión de impostor (otra persona usa este teléfono)"; isChecked = Config.impostorSession }
        col.addView(subject); col.addView(impostor)
        button("Guardar sujeto/sesión") {
            Config.subjectId = subject.text.toString().trim().ifBlank { "S00" }
            Config.impostorSession = impostor.isChecked
            Config.save(this); show("Guardado: ${Config.subjectId} impostor=${Config.impostorSession}")
        }

        // --- Captura ---
        button("Iniciar captura") {
            Capture.start(this, "user")
            show("Captura iniciada en modo ${Config.captureMode}")
        }
        button("Detener captura") { Capture.stop(this); show("Captura detenida") }
        val autostart = CheckBox(this).apply {
            text = "Reiniciar captura al encender el teléfono"
            isChecked = Capture.autostart(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean(Capture.KEY_AUTOSTART, v).apply()
            }
        }
        col.addView(autostart)

        // --- Subida ---
        button("Subir ahora (corte + subida con cualquier red)") {
            Sync.cutAndUploadNow(this); show("Corte y subida en cola. Mira \"Última subida\" arriba.")
        }

        // --- Permisos y restricciones ---
        button("1) Permitir notificaciones") { requestNotifications() }
        button("2) Excluir de optimización de batería") { requestBatteryExemption() }
        button("3) Ajustes de la app (autoinicio / sin restricciones del fabricante)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("Guía por fabricante (dontkillmyapp.com)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/${Build.MANUFACTURER.lowercase()}")))
        }

        // --- Modo de captura ---
        for (m in CaptureMode.values()) button("Modo: ${m.name}") {
            Config.captureMode = m; Config.save(this)
            show("Modo = $m. Detén e inicia la captura para aplicarlo.")
        }

        // --- Diagnóstico ---
        button("Inventario de sensores (fase 0)") { show(SensorSurvey.run(this)) }
        button("Detalles") { show(detailsText()) }

        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(tick) }

    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    // ------------------------------------------------------------------ lista de verificación

    private enum class Level(val color: Int, val mark: String) {
        OK(Color.rgb(46, 125, 50), "✓"),
        WARN(Color.rgb(230, 140, 0), "!"),
        BAD(Color.rgb(198, 40, 40), "✗"),
        INFO(Color.GRAY, "·")
    }

    private fun checklistText(): CharSequence {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val kg = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val notif = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val sp = Sync.prefs(this)
        val lastUp = sp.getLong("last_upload_ms", 0L)
        val lastErr = sp.getString("last_error", null)
        val pending = Sync.pendingZips(this).size
        val hoursSinceUp = if (lastUp > 0) (System.currentTimeMillis() - lastUp) / 3.6e6 else Double.MAX_VALUE
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)

        val items = listOf(
            (if (Config.subjectId != "S00") Level.OK else Level.BAD) to "Sujeto: ${Config.subjectId}" +
                    if (Config.impostorSession) " (sesión de impostor)" else "",
            (if (CaptureService.running) Level.OK else Level.BAD) to
                    if (CaptureService.running) "Captura activa" else "Captura detenida",
            (if (notif) Level.OK else Level.BAD) to "Notificaciones permitidas",
            (if (pm.isIgnoringBatteryOptimizations(packageName)) Level.OK else Level.BAD) to "Excluida de optimización de batería",
            (if (kg.isDeviceSecure) Level.OK else Level.BAD) to "Bloqueo seguro (PIN / huella / rostro)",
            (if (Capture.autostart(this)) Level.OK else Level.WARN) to "Reiniciar al encender",
            Level.INFO to "Autoinicio del fabricante: verificar a mano en Ajustes de la app",
            when {
                BuildConfig.UPLOAD_URL.isBlank() -> Level.BAD
                lastUp == 0L -> Level.WARN
                hoursSinceUp > 36 -> Level.BAD
                else -> Level.OK
            } to (if (lastUp > 0) "Última subida: ${fmt.format(Date(lastUp))}" else "Última subida: nunca") +
                    " · pendientes: $pending" + (lastErr?.let { "\n     error: $it" } ?: ""),
        )
        val sb = SpannableStringBuilder()
        for ((lvl, text) in items) {
            val start = sb.length
            sb.append("${lvl.mark} $text\n")
            sb.setSpan(ForegroundColorSpan(lvl.color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun detailsText(): String = buildString {
        append("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
        append("Modo: ${Config.captureMode} · ${1_000_000 / Config.samplingPeriodUs} Hz · x = ${Config.postUnlockSeconds} s\n")
        append("Datos: ${getExternalFilesDir(null)?.absolutePath}\n")
        append("Subida: ${if (BuildConfig.UPLOAD_URL.isBlank()) "sin URL configurada" else "configurada"}\n")
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        else show("No hace falta en esta versión de Android")
    }

    @SuppressLint("BatteryLife") // Aceptable en una app de investigación que no se publica en Play.
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { show("Ya está exenta"); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun show(s: String) { out.text = s }
}
