package edu.pickupauth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restaura la captura sin que nadie abra la app:
 *  - al encender el teléfono (si "reiniciar al encender" está activo, por defecto sí);
 *  - al actualizar la app (siempre, si la captura estaba activa).
 * Android 12+ permite iniciar un servicio en primer plano desde estos dos broadcasts.
 * El BOOT_COMPLETED llega recién tras el primer desbloqueo, así que ese primer desbloqueo no se captura.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        Lifecycle.log(context, reason)
        if (!Capture.isEnabled(context)) return
        if (reason == "boot" && !Capture.autostart(context)) return
        Capture.start(context, reason)
    }
}
