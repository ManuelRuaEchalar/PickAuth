package edu.pickupauth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Panel de control del investigador: permisos, exención de batería, modo de captura,
 * sujeto/sesión de impostor, inventario de sensores y estado.
 */
class MainActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 64, 40, 40) }
        out = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }

        fun button(label: String, action: () -> Unit) =
            col.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        col.addView(TextView(this).apply { text = "PickupAuth · prototipo de captura"; textSize = 20f })

        // --- Sujeto y sesión ---
        val subject = EditText(this).apply { hint = "ID de sujeto (p. ej. S01)"; setText(Config.subjectId) }
        val impostor = CheckBox(this).apply { text = "Sesión de impostor (otra persona usa este teléfono)"; isChecked = Config.impostorSession }
        col.addView(subject); col.addView(impostor)
        button("Guardar sujeto/sesión") {
            Config.subjectId = subject.text.toString().ifBlank { "S00" }
            Config.impostorSession = impostor.isChecked
            Config.save(this); show("Guardado: ${Config.subjectId} impostor=${Config.impostorSession}")
        }

        // --- Modo de captura ---
        for (m in CaptureMode.values()) button("Modo: ${m.name}") {
            Config.captureMode = m; Config.save(this)
            show("Modo = $m. Reinicia la captura para aplicarlo.")
        }

        // --- Captura ---
        button("Iniciar captura") {
            ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
            show("Captura iniciada en modo ${Config.captureMode}")
        }
        button("Detener captura") {
            stopService(Intent(this, CaptureService::class.java)); show("Captura detenida")
        }
        val autostart = CheckBox(this).apply {
            text = "Reiniciar captura al encender el teléfono"
            isChecked = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("autostart", false)
            setOnCheckedChangeListener { _, v -> getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("autostart", v).apply() }
        }
        col.addView(autostart)

        // --- Permisos y restricciones ---
        button("1) Permitir notificaciones") { requestNotifications() }
        button("2) Excluir de optimización de batería") { requestBatteryExemption() }
        button("3) Ajustes de la app (autoinicio / sin restricciones del fabricante)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("Guía por fabricante (dontkillmyapp.com)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/${Build.MANUFACTURER.lowercase()}")))
        }

        // --- Diagnóstico ---
        button("Inventario de sensores (fase 0)") { show(SensorSurvey.run(this)) }
        button("Estado") { show(statusText()) }

        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
        show(statusText())
    }

    private fun statusText(): String {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val notif = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return buildString {
            append("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
            append("Notificaciones: ${if (notif) "✓" else "✗"}\n")
            append("Exento de optimización de batería: ${if (pm.isIgnoringBatteryOptimizations(packageName)) "✓" else "✗"}\n")
            append("Modo: ${Config.captureMode} · ${1_000_000 / Config.samplingPeriodUs} Hz · x = ${Config.postUnlockSeconds} s\n")
            append("Servicio: ${CaptureService.status}\n")
            append("Datos: ${getExternalFilesDir(null)?.absolutePath}\n")
        }
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
