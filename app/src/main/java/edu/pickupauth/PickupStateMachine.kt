package edu.pickupauth

import kotlin.math.abs
import kotlin.math.max

enum class PhoneState {
    UNKNOWN,
    STORED,      // en bolsillo o bolsa: proximidad "cerca" y oscuridad
    ON_SURFACE,  // quieto y casi plano (mesa)
    HELD,        // fuera del bolsillo, sin uso (en la mano con pantalla apagada, soporte, etc.)
    EXTRACTING,  // transición detectada: sacarlo / levantarlo
    SETTLED,     // se estabilizó tras la sacudida (postura de lectura)
    IN_USE       // desbloqueado
}

enum class Origin { POCKET_OR_BAG, SURFACE, HAND, UNKNOWN }

/**
 * Máquina de estados basada en reglas. Su objetivo NO es autenticar: es decidir
 * cuándo hubo un episodio "sacar → acomodar → desbloquear → usar" y marcar sus tiempos
 * para recortar la ventana. Los umbrales viven en Config y se calibran en la fase 3.
 *
 * Todas las entradas llevan t en ns con la base de SystemClock.elapsedRealtimeNanos().
 */
class PickupStateMachine(private val cb: Callback) {

    interface Callback {
        fun onState(t: Long, from: PhoneState, to: PhoneState, reason: String)
        fun onFalseTrigger(t: Long, origin: Origin)
    }

    var state = PhoneState.UNKNOWN; private set
    var origin = Origin.UNKNOWN; private set
    var tTransition = 0L; private set    // inicio detectado de la toma (0 si no hubo)
    var tSettled = 0L; private set       // momento en que se estabilizó

    private var proxNear: Boolean? = null  // null = el sensor no ha reportado (común con pantalla apagada)
    private var lux: Float? = null
    private var screenOn = false
    private var restGravity: FloatArray? = null
    private var stillSince = 0L
    private var settleSince = 0L
    private var storedSince = 0L

    private val ms = 1_000_000L

    fun onProximity(t: Long, near: Boolean) {
        val prev = proxNear
        proxNear = near
        if (state == PhoneState.STORED && prev == true && !near) startTransition(t, "prox_far")
    }

    fun onLight(t: Long, l: Float) {
        val prev = lux
        lux = l
        if (state == PhoneState.STORED && prev != null &&
            l > max(prev, 0.5f) * Config.lightJumpRatio && l > Config.lightLowLux
        ) startTransition(t, "light_jump")
    }

    fun onMotion(t: Long, variance: Float, g: FloatArray) {
        when (state) {
            PhoneState.UNKNOWN, PhoneState.STORED, PhoneState.ON_SURFACE, PhoneState.HELD -> {
                if (variance < Config.stillVar) { if (stillSince == 0L) stillSince = t } else stillSince = 0L
                // Justo después de guardarlo, el teléfono aún se está acomodando en el bolsillo:
                // la orientación de referencia sigue a la actual y no se evalúa motion_tilt.
                val storing = state == PhoneState.STORED && t - storedSince < Config.storeGuardMs * ms
                if (storing) restGravity = g.copyOf()
                val tilt = restGravity?.let { MotionTracker.angleDeg(it, g) } ?: 0f

                when {
                    state == PhoneState.ON_SURFACE && variance > Config.motionVar ->
                        startTransition(t, "motion_from_surface")
                    // Respaldo cuando proximidad/luz no reportan (o quedan "pegados") con pantalla apagada.
                    // Caminar mueve mucho pero cambia poco la gravedad media; sacar el teléfono la cambia bastante.
                    !storing && state == PhoneState.STORED && variance > Config.motionVar && tilt > Config.tiltDeg ->
                        startTransition(t, "motion_tilt")
                    !screenOn -> evaluateRest(t, g, variance)
                }
            }
            PhoneState.EXTRACTING -> {
                if (!screenOn && t - tTransition > Config.transitionTimeoutMs * ms) { falseTrigger(t); return }
                if (variance < Config.settleVar) {
                    if (settleSince == 0L) settleSince = t
                    if (t - settleSince >= Config.settleMs * ms) {
                        tSettled = settleSince
                        move(t, PhoneState.SETTLED, "settled")
                    }
                } else settleSince = 0L
            }
            PhoneState.SETTLED -> {
                if (!screenOn && t - tTransition > Config.transitionTimeoutMs * ms) falseTrigger(t)
            }
            PhoneState.IN_USE -> Unit
        }
    }

    fun onScreen(t: Long, on: Boolean) {
        screenOn = on
        if (!on) {
            stillSince = 0L
            if (state == PhoneState.IN_USE || state == PhoneState.EXTRACTING || state == PhoneState.SETTLED) {
                move(t, PhoneState.UNKNOWN, "screen_off")
                origin = Origin.UNKNOWN
                tTransition = 0L; tSettled = 0L
            }
        }
    }

    fun onUserPresent(t: Long) {
        if (state != PhoneState.EXTRACTING && state != PhoneState.SETTLED) {
            // Desbloqueo sin toma detectada: también se guarda (sirve para medir la cobertura del detector).
            origin = when (state) {
                PhoneState.STORED -> Origin.POCKET_OR_BAG
                PhoneState.ON_SURFACE -> Origin.SURFACE
                PhoneState.HELD -> Origin.HAND
                else -> Origin.UNKNOWN
            }
            tTransition = 0L; tSettled = 0L
        }
        move(t, PhoneState.IN_USE, "user_present")
    }

    private fun evaluateRest(t: Long, g: FloatArray, variance: Float) {
        val dark = lux?.let { it < Config.lightLowLux } ?: true
        when {
            proxNear == true && dark -> {
                // Mientras está guardado, la orientación de referencia se actualiza (caminar la cambia poco).
                if (variance < Config.motionVar) restGravity = g.copyOf()
                if (state != PhoneState.STORED) {
                    // La referencia anterior era la de la mano o la mesa: se reemplaza por la actual.
                    restGravity = g.copyOf()
                    storedSince = t
                    move(t, PhoneState.STORED, "prox_near_dark")
                }
            }
            stillSince != 0L && t - stillSince > Config.restMs * ms && abs(g[2]) > 9.0f -> {
                restGravity = g.copyOf()
                if (state != PhoneState.ON_SURFACE) move(t, PhoneState.ON_SURFACE, "still_flat")
            }
            proxNear == false && state != PhoneState.HELD && state != PhoneState.ON_SURFACE -> {
                restGravity = g.copyOf()
                move(t, PhoneState.HELD, "out_not_flat")
            }
        }
    }

    private fun startTransition(t: Long, reason: String) {
        origin = when (state) {
            PhoneState.STORED -> Origin.POCKET_OR_BAG
            PhoneState.ON_SURFACE -> Origin.SURFACE
            PhoneState.HELD -> Origin.HAND
            else -> Origin.UNKNOWN
        }
        tTransition = t
        tSettled = 0L
        settleSince = 0L
        move(t, PhoneState.EXTRACTING, reason)
    }

    private fun falseTrigger(t: Long) {
        cb.onFalseTrigger(t, origin)
        origin = Origin.UNKNOWN
        tTransition = 0L; tSettled = 0L
        move(t, PhoneState.UNKNOWN, "timeout_no_screen")
    }

    private fun move(t: Long, to: PhoneState, reason: String) {
        val from = state
        if (from == to) return
        state = to
        cb.onState(t, from, to, reason)
    }
}
