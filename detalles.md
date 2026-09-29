Autenticación por la toma del celular: delimitación y plan del prototipo
Sep 29, 2026 · @Manuel
Delimitación del problema
Se estudia la verificación implícita del dueño en una sola ventana continua: desde que el celular deja su lugar de reposo (bolsillo, bolsa, mesa) hasta x segundos después del desbloqueo, usando solo sensores que Android entrega sin permisos peligrosos.
La ventana tiene cuatro fases, en este orden:
1. Sacar: el teléfono sale del bolsillo, la bolsa o la superficie. Señales: proximidad pasa a lejos, sube la luz, cambia la orientación.
2. Acomodar: elevación hacia la cara, sacudida al frenar el brazo y estabilización en postura de lectura.
3. Desbloquear: pantalla encendida y evento USER_PRESENT (PIN, huella o rostro). Es el ancla temporal de todo el episodio.
4. Primeros x segundos de uso: micro-movimientos y agarre. x es una variable de estudio, no un valor fijo (se barre de 1 a 10 s).
Pregunta de investigación. ¿Un modelo profundo entrenado con aprendizaje métrico o one-class distingue al dueño usando la ventana completa, incluso cuando el teléfono sale del bolsillo, el caso que Secure Pick Up descartó con plantillas DTW?
Hipótesis a contrastar:
• H1: las tomas desde bolsillo o bolsa contienen información distintiva que un modelo aprendido recupera mejor que DTW (misma partición de datos, línea base reimplementada).
• H2: la fusión de fases da menor EER que cualquier fase sola.
• H3: la sub-fase de estabilización aporta información propia (se mide por ablación).
• H4: el EER baja con x y se estanca; existe un x mínimo útil.
Dentro del alcance: Android 10 o superior; solo el teléfono (sin reloj); acelerómetro, giroscopio, magnetómetro, barómetro, proximidad y luz; toques solo en tareas controladas dentro de la app propia, porque una app no puede leer los toques de otras apps.
Fuera del alcance: iOS, cámara, micrófono, ubicación, y reemplazar el desbloqueo. El sistema se evalúa como factor implícito adicional: reducir desbloqueos explícitos o detectar a un impostor justo después de desbloquear.
Arquitectura del prototipo de captura
El prototipo mantiene los sensores siempre activos en un servicio en primer plano, guarda los últimos 20 s en memoria y, al desbloquear, recorta y escribe el episodio; la máquina de estados solo decide cuándo y qué guardar, no autentica.
Capas, de abajo hacia arriba:
1. Captura continua. Servicio en primer plano de tipo health. Acelerómetro, giroscopio y magnetómetro a 100 Hz; barómetro a 20 Hz; proximidad y luz por cambio. Cada sensor escribe en un buffer circular de arreglos primitivos (sin objetos por muestra, sin presión sobre el recolector de basura).
2. Eventos del sistema. Receptores registrados en código para SCREEN_ON, SCREEN_OFF y USER_PRESENT; estos no se pueden declarar en el manifiesto. Todo usa el mismo reloj que los sensores (elapsedRealtimeNanos).
3. Rasgos baratos en línea. Varianza de la magnitud de aceleración en ~0,5 s y gravedad estimada con filtro paso bajo. Solo sirven para la máquina de estados.
4. Máquina de estados por reglas. Estados de reposo (STORED, ON_SURFACE, HELD) → EXTRACTING → SETTLED → IN_USE. Si tras una transición no se enciende la pantalla en 6 s, se registra un falso disparo y vuelve al reposo.
5. Recorte y escritura. Al recibir USER_PRESENT, espera x segundos y guarda la ventana [mín(desbloqueo − 8 s, inicio de la toma − 1 s), desbloqueo + x]. También guarda algunos falsos disparos como ejemplos negativos.
6. Etiquetado. Una notificación pregunta de dónde se sacó el teléfono (bolsillo delantero, trasero, chaqueta, bolsa, mesa, mano, otra persona). Es la verdad de terreno para validar el detector.
Distinguir "lo sacaron" de "movimiento fuerte". Ninguna señal sola alcanza, así que se exige una secuencia:
• Reposo en bolsillo: proximidad cerca y oscuridad.
• Transición: proximidad pasa a lejos, o la luz se multiplica por 5, o hay varianza alta con un cambio de gravedad mayor a 35°. Caminar mueve mucho pero cambia poco la gravedad media; sacar el teléfono la cambia bastante.
• Confirmación: el teléfono se estabiliza y la pantalla se enciende o se desbloquea en menos de 6 s. Sin confirmación, es un falso disparo.
Un desbloqueo sin toma detectada también se guarda: sirve para medir cuántas tomas reales se le escapan al detector.
La segmentación fina en sub-fases (inicio real del movimiento, pico de la sacudida, tiempo de asentamiento) se hace offline sobre los datos guardados. La máquina de estados en el teléfono solo necesita no perder episodios.
Opciones a probar
La decisión técnica que más pesa es cómo tener datos del IMU antes del desbloqueo con la pantalla apagada; el resto de las opciones se comparan una vez resuelta esa.
Modo de captura con pantalla apagada (ya implementados en el esqueleto como CaptureMode):
Modo
Cómo funciona
A favor
En contra
Se decide por
WAKELOCK
Wake lock parcial; sensores sin batching; el CPU nunca duerme
Simple; cobertura completa en cualquier teléfono
El que más batería gasta
%/h de batería en 24 h
WAKEUP_BATCH
Variantes wake-up de los sensores con latencia de 5 s; el hub despierta al CPU al llenarse la FIFO
Cobertura completa con menos despertares
No todos los teléfonos tienen acelerómetro/giroscopio wake-up
Inventario de sensores + batería
FIFO_FLUSH
Sensores normales con batching; en suspensión la FIFO se sobrescribe como buffer circular; al encender la pantalla se vacía con flush()
Casi sin costo de batería
La historia dura lo que la FIFO: fifoMaxEventCount ÷ Hz, compartida entre sensores
Segundos de cobertura previa en episodios reales
Si ningún modo da cobertura y batería aceptables en el teléfono elegido, la salida práctica para la tesis es WAKELOCK en dispositivos dedicados a la recolección, y reportar el costo energético como limitación.
Otras dimensiones:
Dimensión
Variantes
Qué se mide
Frecuencia del IMU
50, 100, 200 Hz; 400 Hz si el hardware lo permite (requiere HIGH_SAMPLING_RATE_SENSORS)
EER por frecuencia; aporte del temblor fino a 200+ Hz
Magnetómetro
Crudo, solo magnitud, cambios relativos, ninguno
EER con y sin él (en Secure Pick Up empeoró el resultado)
Orientación
Gravedad y vector de rotación de juego (sin magnetómetro) vs. solo crudos
Si la orientación explícita ayuda al modelo o es redundante
Barómetro
Con y sin
Si el cambio de altura bolsillo → cara discrimina (depende de la resolución)
Detector de bolsillo
Reglas proximidad + luz; clasificador pequeño solo con IMU (independiente del usuario); híbrido
Recall y precisión contra la etiqueta del usuario
Contexto
Sin contexto vs. Activity Recognition API (quieto, caminando, en vehículo)
Si condicionar el modelo por contexto baja el EER
Ancla temporal
USER_PRESENT vs. SCREEN_ON
Estabilidad del recorte; el rostro puede desbloquear antes de acomodar el teléfono
Etiquetado
Notificación tras cada desbloqueo; video en laboratorio; ambos
Tasa de respuesta y concordancia entre ambos
Herramientas de Android
Todo lo necesario para la captura está en el SDK estándar; Google Play services solo hace falta si se prueba el contexto de actividad.
Herramienta
Para qué se usa
Nota
SensorManager.registerListener(listener, sensor, periodoUs, latenciaMaxUs, handler)
Registrar cada sensor con frecuencia y batching propios, en un hilo dedicado
La latencia máxima > 0 activa el batching
getDefaultSensor(tipo, wakeUp), fifoMaxEventCount, fifoReservedEventCount, minDelay
Inventario: qué modo de captura es viable en cada teléfono
Lo hace SensorSurvey.kt
SensorManager.flush()
Vaciar la FIFO al encender la pantalla o antes de escribir el episodio
Clave del modo FIFO_FLUSH
SensorDirectChannel
Alternativa para frecuencias altas: el sensor escribe en memoria compartida
Opcional; solo si 200+ Hz resulta útil
Servicio en primer plano + ServiceCompat.startForeground(…, FOREGROUND_SERVICE_TYPE_HEALTH)
Mantener el proceso vivo y con acceso a sensores en segundo plano
Notificación visible obligatoria
PowerManager (PARTIAL_WAKE_LOCK, isInteractive, isIgnoringBatteryOptimizations)
Modo WAKELOCK; estado inicial de pantalla; verificar exención

Receptores dinámicos de SCREEN_ON, SCREEN_OFF, USER_PRESENT
Anclas temporales del episodio
No funcionan declarados en el manifiesto
KeyguardManager.isDeviceSecure()
Confirmar que hay bloqueo seguro durante la recolección
Si no hay, USER_PRESENT no implica autenticación
NotificationCompat + una actividad mínima
Muestreo de experiencia: etiqueta de origen tras cada desbloqueo

Activity Recognition Transition API (Play services)
Contexto: quieto, caminando, en vehículo
Permiso en tiempo de ejecución ACTIVITY_RECOGNITION
UsageStatsManager
Qué app se abrió tras desbloquear (contexto de los primeros segundos)
Acceso especial PACKAGE_USAGE_STATS, se concede en Ajustes
MotionEvent dentro de la app propia
Toques en tareas controladas (laboratorio)
Una app no puede leer toques de otras apps
adb shell dumpsys sensorservice
Ver qué sensores están activos, a qué frecuencia y con qué FIFO
Diagnóstico de la fase 0–1
adb shell dumpsys deviceidle force-idle
Forzar Doze para probar que la captura sobrevive
Fase 1
adb shell dumpsys batterystats + Battery Historian / Energy Profiler
Medir el costo energético por modo
Fase 1
PyTorch o TensorFlow → LiteRT (TensorFlow Lite)
Entrenamiento offline y luego inferencia en el teléfono
Fase 6
Permisos y restricciones
Ninguno de los sensores usados pide permiso al usuario; lo que complica la captura son las reglas de ejecución en segundo plano de Android y, sobre todo, las de cada fabricante.
Restricciones de Android y cómo se resuelven en el esqueleto:
Restricción
Efecto si se ignora
Solución
Android 8+: los broadcasts implícitos no llegan a receptores del manifiesto
Nunca se detectan pantalla ni desbloqueo
Registrar SCREEN_ON, SCREEN_OFF y USER_PRESENT en código, dentro del servicio
Android 9+: una app en segundo plano deja de recibir sensores continuos
Buffers vacíos en cuanto se cierra la app
Servicio en primer plano
Android 12+: tope de 200 Hz
No se puede pasar de 200 Hz
Declarar HIGH_SAMPLING_RATE_SENSORS (permiso normal, sin diálogo)
Android 13+: notificaciones requieren permiso
La notificación del servicio y la de etiquetado no se ven
Pedir POST_NOTIFICATIONS al inicio
Android 14+: todo servicio en primer plano declara un tipo
Excepción al arrancar el servicio
Tipo health + FOREGROUND_SERVICE_HEALTH; declarar HIGH_SAMPLING_RATE_SENSORS cumple su requisito (tipos de servicio)
Android 15+: algunos tipos no pueden arrancar desde BOOT_COMPLETED
La captura no vuelve tras reiniciar
health no figura en la lista prohibida; verificarlo en el teléfono en la fase 1
Suspensión del CPU con pantalla apagada
Los sensores normales dejan de entregar datos
Uno de los tres modos de captura
Doze y App Standby
Wake locks ignorados y trabajo diferido en reposo prolongado
Exención de optimización de batería; probar con dumpsys deviceidle force-idle
Administrador de tareas (Android 13+)
El usuario puede detener el servicio
Instrucción explícita a los participantes; stats.csv delata los cortes
Restricciones de fabricantes. Varios fabricantes matan servicios en segundo plano aunque Android los permita. La app ofrece botones que llevan a los ajustes y a la guía de dontkillmyapp.com de cada marca.
• Xiaomi / Redmi / POCO: activar Autoinicio; Ahorro de batería de la app en "Sin restricciones"; bloquear la app en recientes.
• Samsung: Batería de la app en "Sin restricciones"; quitarla de "Apps en suspensión" y "Apps en suspensión profunda".
• Huawei / Honor: Inicio de apps en manual con las tres opciones activas.
• OnePlus / OPPO / realme / vivo: permitir actividad en segundo plano, desactivar optimización y bloquear en recientes.
• Google Pixel: normalmente basta con la exención de batería.
Sensores que se comportan distinto con pantalla apagada. Muchos teléfonos recientes usan proximidad "virtual" (ultrasonido o software) que solo se activa durante llamadas, y la luz puede no reportar con la pantalla apagada. Se verifica en la fase 0 con el teléfono en el bolsillo; si no reportan, la máquina de estados cae al respaldo solo con IMU (cambio de gravedad mayor a 35°).
Configuración del teléfono durante la recolección.
• Bloqueo seguro activo (PIN, huella o rostro).
• Smart Lock / Extend Unlock desactivado: si no, USER_PRESENT ocurre sin autenticación.
• Anotar si hay "levantar para activar" y desbloqueo facial sin deslizar: cambian el orden entre pantalla, estabilización y desbloqueo.
Distribución. La exención de batería y el tipo de servicio no pasarían la revisión de Google Play para este uso. Se instala por APK o con Firebase App Distribution, y se documenta como app de investigación.
Construcción por fases
No se toca ningún modelo hasta que la captura, los eventos y la detección de "lo sacó del bolsillo" pasen sus criterios de salida en uso real; recolectar a escala con una captura defectuosa invalida todo lo que viene después. Las metas numéricas son propuestas de partida: ajústalas con tu director.
Las fases 0 a 3 no producen ningún modelo: solo prueban que cada episodio real queda capturado, bien recortado y bien etiquetado.
1. Fase 0 · Inventario y teléfono objetivo (1 semana).
    ◦ Correr el inventario de sensores en 3 a 5 teléfonos candidatos.
    ◦ Con la pantalla apagada y el teléfono en el bolsillo, comprobar si proximidad y luz siguen reportando.
    ◦ Verificar que el reloj de los sensores coincide con elapsedRealtimeNanos (el evento clock_offset_ms).
    ◦ Sale cuando: hay una tabla por teléfono con modos viables, segundos de FIFO y comportamiento de proximidad; se eligen 1 o 2 teléfonos para todo el estudio.
2. Fase 1 · Captura continua confiable (2 semanas).
    ◦ Cada modo de captura corre 24 h en uso normal, con el teléfono la mayor parte del tiempo en el bolsillo.
    ◦ Pruebas de supervivencia: Doze forzado, reinicio, 72 h seguidas, uso intenso de otras apps.
    ◦ Sale cuando: el modo elegido cubre la ventana previa completa en al menos 95% de los desbloqueos, sin huecos mayores a 50 ms dentro de la ventana, y el consumo extra es aceptable (meta propuesta: ≤ 2 %/h sobre la línea base sin la app).
3. Fase 2 · Eventos y reloj (1 semana, en paralelo con la 1).
    ◦ Registrar pantalla, desbloqueo y método de desbloqueo anotado a mano.
    ◦ Contar desbloqueos durante 3 días y compararlos con los episodios guardados.
    ◦ Sale cuando: 100% de los desbloqueos produce episodio y todos los eventos quedan alineados con los sensores.
4. Fase 3 · Máquina de estados y detector de bolsillo (2 a 3 semanas).
    ◦ Sesión guionizada en laboratorio con video: 5 personas × orígenes (bolsillo delantero, trasero, chaqueta, bolsa, mesa, mano) × sentado, de pie y caminando × 10 repeticiones.
    ◦ Calibrar umbrales con ese video; probar la variante con clasificador solo IMU.
    ◦ Validar la segmentación offline en sub-fases contra el video.
    ◦ Sale cuando (metas propuestas): recall de "sacado de bolsillo o bolsa" ≥ 90%, falsos disparos ≤ 2 por hora en uso diario, concordancia con las etiquetas de la notificación ≥ 85%, error del inicio de toma < 200 ms.
5. Fase 4 · Recolección (4 a 8 semanas).
    ◦ Aprobación ética y consentimiento informado antes de empezar.
    ◦ Protocolo de laboratorio (guionizado) + 2 semanas de uso libre por participante.
    ◦ Sesiones de impostor en el mismo teléfono y ataques de imitación con video de la víctima.
    ◦ Meta: al menos 30 participantes (Secure Pick Up usó 24).
    ◦ Sale cuando: el dataset se congela con un reporte de calidad (cobertura, etiquetas, participantes que abandonaron).
6. Fase 5 · Dataset y líneas base (2 semanas).
    ◦ Segmentación offline, particiones por día o sesión (nunca ventanas mezcladas al azar entre entrenamiento y prueba).
    ◦ Reimplementar DTW ponderado (Secure Pick Up) y Random Forest con rasgos (Please Hold On).
    ◦ Sale cuando: las líneas base dan resultados del mismo orden que sus artículos en las tomas desde reposo.
7. Fase 6 · Modelos (4 a 6 semanas).
    ◦ Codificadores 1D-CNN, TCN o Transformer; aprendizaje métrico siamés o con tripletas; one-class (Deep SVDD, autoencoder).
    ◦ Fusión por fases, barrido de x y ablaciones (sin estabilización, sin magnetómetro, por frecuencia).
    ◦ Sale cuando: H1 a H4 tienen respuesta con intervalos de confianza.
8. Fase 7 · En el dispositivo (2 semanas).
    ◦ Convertir a LiteRT, medir latencia y batería, y probar el enrolamiento con pocas tomas y la actualización por deriva.
Métricas y protocolo de evaluación
Hay dos grupos de métricas: las del sistema de captura (fases 1 a 3, calculadas por analyze_episodes.py) y las de autenticación (fases 5 a 7).
Sistema de captura:
Métrica
Definición
Dónde sale
Cobertura previa
% de desbloqueos con datos IMU desde el inicio de la ventana
acc_late_s en episodes_summary.csv
Hueco máximo
Mayor salto entre muestras dentro de la ventana (ms)
max_gap_ms en meta.json
Consumo
%/h de batería con la app menos %/h sin ella, mismo uso
stats.csv + dumpsys batterystats
Episodios por desbloqueo
Episodios guardados ÷ desbloqueos reales
Fase 2
Recall y precisión del detector de bolsillo
Origen detectado vs. etiqueta del usuario o del video
Matriz en analyze_episodes.py
Falsos disparos por hora
Transiciones sin pantalla encendida
stats.csv
Error del inicio de toma
Diferencia entre el inicio detectado y el del video (ms)
Fase 3
Autenticación:
• EER, y FAR con FRR fijo (por ejemplo FRR = 5%), por usuario y agregado, con intervalos por bootstrap sobre usuarios.
• Desglose por origen (bolsillo, bolsa, mesa, mano) y por contexto (sentado, de pie, caminando): es donde se contrasta H1.
• Curva de EER contra x (H4) y contra la fase usada (H2, H3).
• Métrica de usabilidad de Secure Pick Up: % de desbloqueos explícitos que se evitarían.
Protocolo, para que los números sean creíbles:
• Separación temporal. Enrolar con los primeros días o sesiones y probar con días posteriores; nunca mezclar ventanas del mismo episodio entre entrenamiento y prueba.
• Impostores en el mismo teléfono. Además de usar datos de otros participantes, grabar sesiones de otras personas en el teléfono del dueño, para que el modelo no reconozca el aparato en vez de la persona.
• Ataques. Impostor casual (sin información), con contexto (sabe dónde guarda el teléfono) e imitación (vio un video del dueño), como en Secure Pick Up.
• Deriva. Reportar el rendimiento por semana transcurrida desde el enrolamiento.
• Privacidad. La ventana incluye el ingreso del PIN, y el IMU durante el tecleo puede revelarlo: guardar los datos cifrados, no publicarlos crudos y evaluar si se excluye ese tramo.
Riesgos
El riesgo principal es técnico y se conoce en la fase 0: que el teléfono elegido no permita capturar la ventana previa sin gastar demasiada batería.
Riesgo
Señal temprana
Plan B
Proximidad y luz no reportan con pantalla apagada
Inventario de la fase 0 en el bolsillo
Detector de bolsillo solo con IMU
FIFO pequeña y sin sensores wake-up
fifoMaxEventCount ÷ Hz menor a 5 s
WAKELOCK en teléfonos dedicados; reportar el costo como limitación
El fabricante mata el servicio
Huecos de horas en stats.csv
Cambiar a Pixel o Samsung para la recolección
Pocas etiquetas por notificación
Tasa de respuesta menor a 50% en la fase 3
Sesiones de laboratorio con video como verdad de terreno principal
El desbloqueo facial ocurre antes de acomodar el teléfono
screen_to_unlock_ms muy corto
Usar SCREEN_ON como ancla y tratar el orden de fases como variable
El modelo aprende el teléfono y no a la persona
EER mucho mejor entre teléfonos que dentro del mismo
Sesiones de impostor en el mismo teléfono
H1 resulta falsa (el bolsillo no discrimina)
Fase 6
Sigue siendo un resultado publicable; el aporte pasa a la fusión con estabilización y uso inicial
Referencias
• Secure Pick Up: Implicit Authentication When You Start Using the Smartphone (Lee et al., SACMAT 2017)
• Please Hold On: Unobtrusive User Authentication using Smartphone's Built-in Sensors (Buriro et al., ISBA 2017)
• HMOG: New Behavioral Biometric Features for Continuous Authentication of Smartphone Users (IEEE TIFS)
• AuthentiSense / Towards Continuous Authentication on Mobile Phones using Deep Learning Models
• BehavePassDB (Pattern Recognition)
• Android: Batching de sensores (AOSP)
• Android: Tipos de sensores (AOSP)
• Android: Tipos de servicio en primer plano
• Don't kill my app!