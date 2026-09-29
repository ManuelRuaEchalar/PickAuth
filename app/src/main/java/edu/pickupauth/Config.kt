package edu.pickupauth

import android.content.Context

/**
 * Estrategia para tener datos del IMU ANTES del desbloqueo con la pantalla apagada.
 * Es la decisión técnica más importante de la fase 1: se prueban las tres y se compara
 * cobertura de la ventana previa al desbloqueo vs. consumo de batería.
 */
enum class CaptureMode {
    /** Wake lock parcial + sensores sin batching. Simple y fiable; es la que más batería gasta. */
    WAKELOCK,
    /** Variantes wake-up de los sensores con batching: el hub despierta al CPU cuando se llena la FIFO. */
    WAKEUP_BATCH,
    /** Sensores no wake-up: durante el suspend la FIFO actúa como buffer circular;
     *  al encenderse la pantalla se hace flush() y llegan los últimos segundos. Depende del tamaño de FIFO. */
    FIFO_FLUSH
}

/** Parámetros del prototipo. Los umbrales son valores de arranque: se calibran en la fase 3. */
object Config {
    // --- Muestreo ---
    var samplingPeriodUs = 10_000            // 100 Hz. Sin HIGH_SAMPLING_RATE_SENSORS el tope es 200 Hz.
    var captureMode = CaptureMode.WAKELOCK
    var batchLatencyUs = 5_000_000           // latencia máxima de reporte para WAKEUP_BATCH / FIFO_FLUSH
    var useMagnetometer = true
    var useBarometer = true

    // --- Buffer y ventana del episodio ---
    var bufferSeconds = 20                   // lo que se guarda "hacia atrás" en memoria
    var preUnlockSeconds = 8L                // cuánto antes del desbloqueo se recorta
    var postUnlockSeconds = 5L               // la "x" de la ventana: segundos de uso tras desbloquear
    var marginSeconds = 1L                   // margen antes del inicio de la transición detectada

    // --- Máquina de estados ---
    var stillVar = 0.05f                     // var(|a|) (m/s²)² bajo la cual el teléfono está quieto
    var settleVar = 0.4f                     // var(|a|) bajo la cual se considera "asentado" en la mano
    var motionVar = 1.5f                     // var(|a|) sobre la cual hay movimiento fuerte
    var tiltDeg = 35f                        // cambio de orientación de gravedad que cuenta como toma
    var lightLowLux = 5f                     // oscuridad típica de bolsillo/bolsa
    var lightJumpRatio = 5f                  // subida brusca de luz al salir
    var settleMs = 300L                      // tiempo sostenido bajo settleVar para declarar "estabilizado"
    var transitionTimeoutMs = 6_000L         // si no se enciende la pantalla en este tiempo: falso disparo
    var restMs = 2_000L                      // tiempo quieto para declarar reposo (mesa)

    // --- Metadatos del estudio ---
    var subjectId = "S00"
    var impostorSession = false              // true cuando otra persona usa el teléfono del sujeto

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        captureMode = CaptureMode.valueOf(p.getString("mode", captureMode.name)!!)
        samplingPeriodUs = p.getInt("period_us", samplingPeriodUs)
        postUnlockSeconds = p.getLong("post_s", postUnlockSeconds)
        subjectId = p.getString("subject", subjectId)!!
        impostorSession = p.getBoolean("impostor", impostorSession)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("mode", captureMode.name)
            .putInt("period_us", samplingPeriodUs)
            .putLong("post_s", postUnlockSeconds)
            .putString("subject", subjectId)
            .putBoolean("impostor", impostorSession)
            .apply()
    }
}
