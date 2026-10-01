# Versiones de la app

Qué cambia entre versiones y qué hay que tener en cuenta al analizar datos que mezclan versiones.
Cada episodio desde la 0.3 trae `app_version` en `meta.json`; los zips traen `app_version` en `manifest.json`
desde la 0.2.

| Versión | Fecha | Commit | Quién la tiene |
|---|---|---|---|
| 0.1-captura | 2026-09-29 | `5e046e4` | Solo pruebas internas (prueba A, `datos/`) |
| 0.2-recoleccion | 2026-09-29 | `babe046` | S01–S08 al inicio de la recolección |
| 0.3-recoleccion | 2026-10-01 | `ba7125b` | S01 desde el 01/10 10:07; participantes nuevos |

## Distribución

- APK: `app/build/outputs/apk/debug/pickupauth-0.3-recoleccion.apk` (build debug).
- Todas las versiones se firman con la clave debug de la máquina de desarrollo. Con la misma clave, la
  actualización se instala encima (`adb install -r`) y conserva episodios pendientes, sujeto y configuración.
  Si la clave cambia, habría que desinstalar, y eso borra los episodios que no se han subido.
- La URL y la clave de subida se leen de `local.properties` al compilar.

## 0.3-recoleccion

Motivo: en el primer día de recolección S06 (TECNO KJ6, HiOS) no guardó ningún desbloqueo porque no le
llegaban `SCREEN_ON` ni `USER_PRESENT`, y MIUI mató el servicio decenas de veces sin que supiéramos por qué.

Cambios:

- **Respaldo por sondeo.** Cada 200 ms se consulta `PowerManager.isInteractive` y
  `KeyguardManager.isKeyguardLocked`. Si el broadcast no llegó, `screen_on`, `screen_off` o `user_present`
  salen del sondeo. Un desbloqueo por sondeo exige haber visto el keyguard puesto, para no inventar
  desbloqueos cuando la pantalla se enciende dentro del retardo de bloqueo.
- **Fuente de cada evento.** En `events.csv` el valor de `screen_on`, `screen_off` y `user_present` es
  `broadcast` o `poll`. `meta.json` lleva `event_sources` (`screen_on`, `user_present`) y `app_version`.
  Si el broadcast llega después del sondeo se anota `late_broadcast` con el retraso en ms.
- **Motivo de las muertes del proceso.** Al arrancar, `lifecycle.csv` registra `exit_reason`
  (`ApplicationExitInfo`, Android 11+) de cada muerte anterior, y `crash` para excepciones no capturadas.
- **Umbral de hueco** en `stats.csv`: 3 × max(periodo pedido, `minDelay` del sensor), antes 30 ms fijos.

### Diferencias con la 0.2 que afectan el análisis

**Marcas de tiempo de pantalla y desbloqueo.** En el Xiaomi 23129RA5FL de S01 (prueba del 01/10, 3
desbloqueos), el broadcast llegó tarde respecto al sondeo:

| Evento | Retraso del broadcast |
|---|---|
| `screen_on` | 474–683 ms |
| `user_present` | 39–93 ms |

Por eso, en la 0.3:

- `t_screen_on_ns` queda ~0,5 s antes que en la 0.2, más cerca del momento real.
- `t_user_present_ns` (el ancla de la ventana) queda ~0,1 s antes. Frente a la ventana de 8 s es despreciable.
- `screen_to_unlock_ms` sale ~0,5 s más largo que en la 0.2. **No mezclar este valor entre versiones** sin
  separar por `app_version` o corregir con `event_sources`.
- Un evento por sondeo puede ir hasta 200 ms tarde respecto al momento real (periodo del sondeo).

Los datos de sensores y el recorte de la ventana no cambian. `analyze_episodes.py` trata los episodios sin
`event_sources` como `broadcast`.

**Huecos en `stats.csv`.** Hasta la 0.2, `mag_gaps` y `mag_gap_s` están inflados en los teléfonos con el
magnetómetro a 50 Hz (S02, S03, S04, S07): cada muestra con algo de jitter pasaba el umbral de 30 ms. No
usar esas columnas de la 0.2; `analyze_episodes.py` mide la pérdida con la tasa de muestras (`*_n`), que
vale para ambas versiones.

**S06 con la 0.2** no tiene desbloqueos: sus falsos disparos incluyen tomas reales sin confirmación de pantalla.
Sus datos solo sirven desde que tenga la 0.3.

### Primeros motivos de muerte registrados (S01)

Al instalar la 0.3, Android devolvió las muertes anteriores del proceso:

- `other` · `ScreenOffCPUCheckKill`: MIUI mata la app si con la pantalla apagada usa más del 6 % de CPU en
  25 min (S01 usó 6,7 %). Es la causa probable de los reinicios de S03, S04 y S08; confirmarlo cuando tengan
  la 0.3. Si se confirma, bajar el costo con pantalla apagada (p. ej. IMU a 50 Hz).
- `other` · `OneKeyClean`: limpieza de apps recientes por el usuario.
- `user_requested` · `installPackageLI`: reinstalaciones de la app.

## 0.2-recoleccion

Captura sin supervisión: relanzamiento tras reinicio, actualización y vigilante cada 15 min; `lifecycle.csv`
con latidos; corte diario a las 00:00 y subida a Drive con `manifest.json`; borrado de episodios 3 días
después de subidos.

## 0.1-captura

Esqueleto de captura (fases 0–3) usado en la prueba A del 29/09.
