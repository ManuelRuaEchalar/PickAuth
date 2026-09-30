package edu.pickupauth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Servicio en primer plano (tipo "health") que:
 *  1. mantiene los sensores registrados en todo momento y llena buffers circulares;
 *  2. escucha pantalla encendida/apagada y desbloqueo (USER_PRESENT);
 *  3. alimenta la máquina de estados;
 *  4. al desbloquear, espera `postUnlockSeconds` y guarda el episodio [inicio de la toma − margen, desbloqueo + x];
 *  5. cada 10 min escribe estadísticas de huecos y batería (criterio de salida de la fase 1).
 *
 * Todo el procesamiento corre en un único hilo (sensorThread) para no necesitar locks en la máquina de estados.
 */
class CaptureService : Service(), SensorEventListener, PickupStateMachine.Callback {

    private lateinit var sm: SensorManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var writer: EpisodeWriter
    private lateinit var motion: MotionTracker
    private lateinit var fsm: PickupStateMachine
    private var wakeLock: PowerManager.WakeLock? = null

    private val events = EventLog()
    private val streams = LinkedHashMap<String, Vec3Ring>()
    private val names = HashMap<Sensor, String>()
    private val lastT = HashMap<String, Long>()
    private val count = HashMap<String, Long>()
    private val gaps = HashMap<String, Long>()
    private val gapNs = HashMap<String, Long>()
    private val clockChecked = HashSet<String>()

    private var started = false
    private var screenOn = true
    private var tScreenOn = 0L
    private var pendingDump: Runnable? = null
    private var episodes = 0
    private var withPickup = 0
    private var inPlace = 0
    private var falseTriggers = 0
    private var lastState = ""
    private var lastStateWallMs = 0L
    private var lastFalseDumpNs = 0L

    // ------------------------------------------------------------------ ciclo de vida

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Config.load(this)
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, NOTIF_CAPTURE, captureNotification(), type)
        if (!started) {
            started = true
            setup()
        }
        return START_STICKY
    }

    private fun setup() {
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        writer = EpisodeWriter(this)
        thread = HandlerThread("capture").apply { start() }
        handler = Handler(thread.looper)

        val rateHz = 1_000_000 / Config.samplingPeriodUs
        val imuCap = (Config.bufferSeconds * rateHz * 1.3).toInt()
        motion = MotionTracker(windowSamples = maxOf(10, rateHz / 2))   // ~0,5 s
        fsm = PickupStateMachine(this)
        val pmNow = getSystemService(POWER_SERVICE) as PowerManager
        screenOn = pmNow.isInteractive
        fsm.onScreen(SystemClock.elapsedRealtimeNanos(), screenOn)
        if (Config.captureMode == CaptureMode.WAKELOCK) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pickupauth:capture").apply {
                setReferenceCounted(false); acquire()
            }
        }

        val imuLatency = when (Config.captureMode) {
            CaptureMode.WAKELOCK -> 100_000                 // 100 ms: menos interrupciones, CPU despierto igual
            CaptureMode.WAKEUP_BATCH, CaptureMode.FIFO_FLUSH -> Config.batchLatencyUs
        }
        register("acc", Sensor.TYPE_ACCELEROMETER, Config.samplingPeriodUs, imuLatency, imuCap)
        register("gyr", Sensor.TYPE_GYROSCOPE, Config.samplingPeriodUs, imuLatency, imuCap)
        if (Config.useMagnetometer)
            register("mag", Sensor.TYPE_MAGNETIC_FIELD, Config.samplingPeriodUs, imuLatency, imuCap)
        if (Config.useBarometer)
            register("prs", Sensor.TYPE_PRESSURE, 50_000, imuLatency, Config.bufferSeconds * 30)
        register("lux", Sensor.TYPE_LIGHT, 200_000, 0, 4000)
        register("prox", Sensor.TYPE_PROXIMITY, 200_000, 0, 4000)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Estos broadcasts solo llegan a receptores registrados en código (no en el manifiesto).
        ContextCompat.registerReceiver(this, screenReceiver, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)

        handler.postDelayed(statsTick, STATS_PERIOD_MS)
        val kg = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        events.add(SystemClock.elapsedRealtimeNanos(), "service_start",
            "mode=${Config.captureMode};secure_lock=${kg.isDeviceSecure}")
        refreshStatus()
    }

    override fun onDestroy() {
        if (started) {
            sm.unregisterListener(this)
            unregisterReceiver(screenReceiver)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        status = "detenido"
        super.onDestroy()
    }

    private fun register(name: String, type: Int, periodUs: Int, latencyUs: Int, cap: Int) {
        val s = when (Config.captureMode) {
            CaptureMode.WAKEUP_BATCH -> sm.getDefaultSensor(type, true) ?: sm.getDefaultSensor(type)
            else -> sm.getDefaultSensor(type)
        }
        if (s == null) {
            events.add(SystemClock.elapsedRealtimeNanos(), "sensor_missing", name); return
        }
        names[s] = name
        streams[name] = Vec3Ring(cap)
        val ok = sm.registerListener(this, s, periodUs, latencyUs, handler)
        events.add(SystemClock.elapsedRealtimeNanos(), "sensor_registered",
            "$name;ok=$ok;wakeup=${s.isWakeUpSensor};fifoMax=${s.fifoMaxEventCount};fifoRes=${s.fifoReservedEventCount}")
    }

    // ------------------------------------------------------------------ sensores

    override fun onSensorChanged(e: SensorEvent) {
        val name = names[e.sensor] ?: return
        val t = e.timestamp
        val ring = streams[name] ?: return
        val v = e.values

        if (clockChecked.add(name)) {
            // Verifica que el reloj del sensor sea el de elapsedRealtimeNanos (fase 0).
            events.add(t, "clock_offset_ms", "$name:${(SystemClock.elapsedRealtimeNanos() - t) / 1e6}")
        }
        trackGaps(name, t)

        when (name) {
            "acc" -> {
                ring.add(t, v[0], v[1], v[2])
                motion.update(v[0], v[1], v[2])
                fsm.onMotion(t, motion.variance(), motion.gravity)
            }
            "gyr", "mag" -> ring.add(t, v[0], v[1], v[2])
            "prs" -> ring.add(t, v[0])
            "lux" -> { ring.add(t, v[0]); fsm.onLight(t, v[0]) }
            "prox" -> {
                ring.add(t, v[0])
                // Muchos sensores son binarios: reportan maximumRange para "lejos".
                fsm.onProximity(t, v[0] < e.sensor.maximumRange)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        names[sensor]?.let { events.add(SystemClock.elapsedRealtimeNanos(), "accuracy", "$it:$accuracy") }
    }

    private fun trackGaps(name: String, t: Long) {
        count[name] = (count[name] ?: 0L) + 1
        val prev = lastT[name]
        lastT[name] = t
        if (name !in IMU || prev == null) return
        val dt = t - prev
        if (dt > 3L * Config.samplingPeriodUs * 1000L) {
            gaps[name] = (gaps[name] ?: 0L) + 1
            gapNs[name] = (gapNs[name] ?: 0L) + dt
        }
    }

    // ------------------------------------------------------------------ pantalla / desbloqueo

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val t = SystemClock.elapsedRealtimeNanos()
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true; tScreenOn = t
                    events.add(t, "screen_on")
                    fsm.onScreen(t, true)
                    // En modos con batching: vaciar la FIFO para tener los segundos previos.
                    if (Config.captureMode != CaptureMode.WAKELOCK) sm.flush(this@CaptureService)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    events.add(t, "screen_off")
                    fsm.onScreen(t, false)
                }
                Intent.ACTION_USER_PRESENT -> {
                    events.add(t, "user_present")
                    fsm.onUserPresent(t)
                    scheduleUnlockDump(t)
                }
            }
        }
    }

    override fun onState(t: Long, from: PhoneState, to: PhoneState, reason: String) {
        events.add(t, "state", "$from>$to:$reason")
        lastState = "$to ($reason)"
        lastStateWallMs = System.currentTimeMillis()
        refreshStatus()
    }

    override fun onFalseTrigger(t: Long, origin: Origin) {
        falseTriggers++
        events.add(t, "false_trigger", origin.name)
        refreshStatus()
        // Se guardan como negativos para calibrar el detector, con límite para no llenar el disco.
        if (t - lastFalseDumpNs > 120 * SEC) {
            lastFalseDumpNs = t
            val tOnset = motionOnset(fsm.tTransition)
            var from = fsm.tTransition - (Config.marginSeconds + 2) * SEC
            if (tOnset > 0) from = min(from, tOnset - Config.marginSeconds * SEC)
            val meta = JSONObject().put("origin_detected", origin.name)
                .put("t_motion_onset_ns", tOnset)
                .put("t_transition_ns", fsm.tTransition)
            writer.write("false_trigger", from, t, streams, events.slice(from, t), meta)
        }
    }

    // ------------------------------------------------------------------ episodios

    private fun scheduleUnlockDump(tUnlock: Long) {
        pendingDump?.let { handler.removeCallbacks(it) }
        // Se congela lo que sabía la máquina de estados en el momento del desbloqueo.
        val origin = fsm.origin
        val tTr = fsm.tTransition
        val tSet = fsm.tSettled
        val tScr = tScreenOn
        val r = Runnable {
            // Vaciar FIFOs para que lleguen las muestras de los últimos segundos antes de escribir.
            if (Config.captureMode != CaptureMode.WAKELOCK) sm.flush(this)
            handler.postDelayed({ writeUnlockEpisode(tUnlock, origin, tTr, tSet, tScr) }, 500)
        }
        pendingDump = r
        handler.postDelayed(r, Config.postUnlockSeconds * 1000)
    }

    private fun motionOnset(anchor: Long): Long {
        val acc = streams["acc"]?.slice(anchor - 7 * SEC, anchor) ?: return 0L
        val gyr = streams["gyr"]?.slice(anchor - 7 * SEC, anchor) ?: return 0L
        return EpisodeAnalysis.motionOnset(acc, gyr, anchor)
    }

    private fun writeUnlockEpisode(tUnlock: Long, origin: Origin, tTr: Long, tSet: Long, tScr: Long) {
        val preFrom = tUnlock - Config.preUnlockSeconds * SEC
        val to = tUnlock + Config.postUnlockSeconds * SEC
        // El movimiento real empieza antes del disparo: se busca el último tramo quieto hacia atrás.
        val anchor = if (tTr > 0) tTr else if (tScr > 0) min(tScr, tUnlock) else tUnlock
        val tOnset = motionOnset(anchor)
        var from = preFrom
        if (tTr > 0) from = min(from, tTr - Config.marginSeconds * SEC)
        if (tOnset > 0) from = min(from, tOnset - Config.marginSeconds * SEC)
        val meta = JSONObject()
            .put("origin_detected", origin.name)
            .put("pickup_detected", tTr > 0)
            .put("t_motion_onset_ns", tOnset)
            .put("t_transition_ns", tTr)
            .put("t_settled_ns", tSet)
            .put("t_screen_on_ns", tScr)
            .put("t_user_present_ns", tUnlock)
            .put("screen_to_unlock_ms", if (tScr > 0) (tUnlock - tScr) / 1e6 else -1.0)
        // Sin toma y sin moverse de su sitio en toda la ventana (p. ej. desbloqueado sobre la mesa):
        // se guarda aparte y no se pregunta, porque ya se sabe que no se levantó.
        val inPlaceEp = tTr == 0L && EpisodeAnalysis.stayedInPlace(
            streams["acc"]?.slice(preFrom, to) ?: emptyList(), preFrom, to)
        meta.put("moved", !inPlaceEp)
        val dir = writer.write(if (inPlaceEp) "in_place" else "unlock", from, to, streams, events.slice(from, to), meta)
        episodes++
        when {
            tTr > 0 -> withPickup++
            inPlaceEp -> inPlace++
        }
        refreshStatus()
        if (!inPlaceEp) askForLabel(dir, pickupDetected = tTr > 0)
        updateCaptureNotification()
    }

    // ------------------------------------------------------------------ estadísticas (fase 1)

    private val statsTick = object : Runnable {
        override fun run() {
            writeStats()
            handler.postDelayed(this, STATS_PERIOD_MS)
        }
    }

    private fun writeStats() {
        val f = File(writer.root.parentFile, "stats.csv")
        val header = !f.exists()
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cols = listOf("acc", "gyr", "mag")
        f.appendText(buildString {
            if (header) append("wall_ms,elapsed_ns,mode,screen_on,battery_pct,episodes,false_triggers," +
                    cols.joinToString(",") { "${it}_n,${it}_gaps,${it}_gap_s" } + "\n")
            append("${System.currentTimeMillis()},${SystemClock.elapsedRealtimeNanos()},${Config.captureMode},$screenOn,$battery,$episodes,$falseTriggers,")
            append(cols.joinToString(",") { "${count[it] ?: 0},${gaps[it] ?: 0},${(gapNs[it] ?: 0L) / 1e9}" })
            append("\n")
        })
        count.clear(); gaps.clear(); gapNs.clear()
        refreshStatus()
    }

    /** Estado en vivo para el panel: se actualiza en cada cambio de estado, episodio o falso disparo. */
    private fun refreshStatus() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hhmmss = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        status = buildString {
            append("activo, modo ${Config.captureMode}, batería $battery%\n")
            append("  estado: ${lastState.ifEmpty { fsm.state.name }}")
            if (lastStateWallMs > 0) append(" desde ${hhmmss.format(java.util.Date(lastStateWallMs))}")
            append("\n  desbloqueos: $episodes (con toma: $withPickup, sin toma: ${episodes - withPickup - inPlace}, en su sitio: $inPlace)\n")
            append("  falsos disparos: $falseTriggers")
        }
    }

    // ------------------------------------------------------------------ notificaciones

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CAPTURE, "Captura", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_LABEL, "Etiquetado", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun captureNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("PickupAuth capturando")
            .setContentText("Modo ${Config.captureMode} · episodios: $episodes")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateCaptureNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_CAPTURE, captureNotification())
    }

    /** Muestreo de experiencia: el usuario indica de dónde sacó el teléfono (etiqueta de verdad de terreno). */
    private fun askForLabel(dir: File, pickupDetected: Boolean) {
        val i = Intent(this, LabelActivity::class.java).putExtra(LabelActivity.EXTRA_DIR, dir.name)
        val pi = PendingIntent.getActivity(this, dir.name.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_LABEL)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle(if (pickupDetected) "¿De dónde sacaste el teléfono?" else "¿Levantaste el teléfono antes de desbloquear?")
            .setContentText("Toca para etiquetar el último desbloqueo")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_LABEL, n)
    }

    companion object {
        const val SEC = 1_000_000_000L
        val IMU = setOf("acc", "gyr", "mag")
        const val STATS_PERIOD_MS = 10 * 60 * 1000L
        const val NOTIF_CAPTURE = 1
        const val NOTIF_LABEL = 2
        const val CH_CAPTURE = "capture"
        const val CH_LABEL = "label"
        @Volatile var status: String = "detenido"
    }
}
