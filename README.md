# PickupAuth — prototipo de captura (fases 0–3)

App Android de investigación que captura, en segundo plano y de forma continua, el episodio
**sacar el teléfono → acomodarlo → desbloquear → primeros x segundos de uso**, y lo guarda
como un conjunto de archivos por episodio. No autentica todavía: produce el dataset y valida
que la captura y la detección de estados funcionan antes de pasar al modelo.

> El código no se compiló en este entorno (no hay SDK de Android). Primer paso: abrir la carpeta
> en Android Studio, dejar que sincronice Gradle y actualizar versiones de AGP/Kotlin si lo pide.

## Estructura

| Archivo | Qué hace |
|---|---|
| `Config.kt` | Parámetros: modo de captura, frecuencia, ventana (pre/post), umbrales de la máquina de estados, sujeto. |
| `CaptureService.kt` | Servicio en primer plano tipo `health`. Registra sensores, llena buffers, escucha pantalla/desbloqueo, recorta y guarda episodios, escribe `stats.csv` cada 10 min. |
| `RingBuffer.kt` | Buffers circulares de arreglos primitivos (últimos ~20 s) y registro de eventos. |
| `MotionTracker.kt` | Varianza de \|a\| (~0,5 s) y gravedad por paso bajo, para la máquina de estados. |
| `PickupStateMachine.kt` | Reglas: `STORED`, `ON_SURFACE`, `HELD` → `EXTRACTING` → `SETTLED` → `IN_USE`; falsos disparos por timeout. |
| `EpisodeWriter.kt` | Un directorio por episodio: `acc/gyr/mag/prs/lux/prox.csv`, `events.csv`, `meta.json`, `label.json`. |
| `LabelActivity.kt` | Muestreo de experiencia: tras cada desbloqueo, una notificación pregunta de dónde se sacó el teléfono. |
| `SensorSurvey.kt` | Fase 0: inventario de sensores (wake-up, FIFO, frecuencias) → `sensor_survey.json`. |
| `MainActivity.kt` | Panel del investigador: permisos, exención de batería, modo, sujeto/impostor, estado. |
| `BootReceiver.kt` | Reinicia la captura al encender el teléfono (opcional). |
| `tools/analyze_episodes.py` | Análisis offline: cobertura por modo, huecos, detectado vs. etiqueta, falsos disparos/hora, gráficos. |

## Uso rápido

1. Instalar en el teléfono de prueba (Android 10+). Configurar **bloqueo seguro** (PIN/huella/rostro) y
   **desactivar Smart Lock / Extend Unlock**: si no, `USER_PRESENT` se dispara sin autenticación real.
2. En la app: *Permitir notificaciones* → *Excluir de optimización de batería* → *Ajustes de la app*
   (en Xiaomi/Huawei/Samsung/OnePlus activar autoinicio y "sin restricciones"; ver dontkillmyapp.com).
3. *Inventario de sensores* y guardar el resultado.
4. Elegir modo y *Iniciar captura*. Usar el teléfono normalmente; etiquetar desde la notificación.
5. Extraer y analizar:

```bash
adb pull /sdcard/Android/data/edu.pickupauth/files ./datos
pip install pandas numpy matplotlib
python tools/analyze_episodes.py ./datos --plot 10
```

## Formato de un episodio

```
episodes/unlock_20261005_143012_120/
  acc.csv gyr.csv mag.csv prs.csv lux.csv prox.csv   # t_ns,x,y,z  (t_ns = reloj del sensor, base elapsedRealtimeNanos)
  events.csv                                         # screen_on, user_present, state (transiciones), etc.
  meta.json                                          # origen detectado, t_transition/t_settled/t_screen_on/t_user_present,
                                                     # cobertura por sensor, dispositivo, modo, sujeto, impostor
  label.json                                         # etiqueta del usuario (si respondió)
```

La ventana es `[min(desbloqueo − pre, inicio_toma − margen), desbloqueo + x]`. La segmentación
fina en sub-fases se hace offline; la máquina de estados solo decide *cuándo* y *qué* guardar.

Desde la 0.3: `meta.json` lleva `app_version` y `event_sources` (`broadcast` o `poll`) para pantalla
y desbloqueo. `poll` significa que el broadcast no llegó y el evento salió del sondeo cada 200 ms
(puede ir hasta 200 ms tarde). `events.csv` anota `late_broadcast` cuando el broadcast llega después
del sondeo, y `lifecycle.csv` registra `exit_reason` (motivo de cada muerte del proceso, Android 11+)
y `crash`. Los datos de la 0.2 no traen estos campos; `analyze_episodes.py` los trata como `broadcast`.
