package edu.pickupauth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Reinicia la captura tras reiniciar el teléfono si el usuario lo activó.
 * Nota: Android 15 prohíbe iniciar ciertos tipos de servicio (dataSync, mediaPlayback, cámara, micrófono…)
 * desde BOOT_COMPLETED; "health" no está en esa lista, pero verifícalo en tu dispositivo (fase 1).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val autostart = context.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (autostart) ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java))
    }
}
