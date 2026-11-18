# KMRentable · app Android para conductores

Cuando llega una solicitud en **Uber, Cabify o DiDi**, KMRentable lee la pantalla y muestra un panel
grande con el **valor por km**, el pago por hora, los km a manejar (recogida + viaje), los minutos y si la
carrera es **✅ RENTABLE** o **❌ NO RENTABLE** según tu mínimo por km.

La app **solo muestra información**: no acepta ni rechaza viajes. Nada sale del teléfono.

| Dato             | Cómo se calcula                                  |
| ---------------- | ------------------------------------------------ |
| **Valor km**     | tarifa ÷ km totales (recogida + viaje)           |
| **Por hora**     | tarifa ÷ minutos totales × 60                    |
| **Km a manejar** | km de recogida + km del viaje                    |
| **Faltan / Te queda** | cuánto faltaría para el mínimo, o la ganancia neta si configuras tu costo por km |

## Bitácora y ganancia real

Durante el turno, si después de una solicitud aparece la pantalla de ir a buscar al pasajero ("Navegar",
"PIN obligatorio", "Iniciar viaje", "Llegué"...), la carrera se anota sola. En **📒 Bitácora y ganancias**:

- Ganancia real de **hoy, la semana (desde el lunes) y el mes**, siempre a la vista.
- Ganancia real = monto "para ti" − retención de impuestos (15,25% en 2026, configurable) − combustible
  (km de recogida + viaje × precio del litro ÷ km por litro) − otros costos por km. La comisión de la app
  ya viene descontada en el monto de la solicitud.
- $/km y $/hora reales (las horas salen de la duración de los turnos).
- Corregir, borrar o agregar carreras a mano, y exportar a Excel (CSV con ";" y coma decimal).

- **Importar historial**: en la bitácora, "Importar carreras del historial de Uber / Cabify". Mientras está
  activo, cada detalle de viaje que se abre (Uber › Ganancias › viaje; Cabify › viaje) se anota con su
  fecha, hora, monto final, distancia y duración. Los datos de un mismo viaje se juntan aunque haya que bajar
  (Cabify), y no se duplican. Como el historial no muestra los km de recogida, se suma un promedio configurable.
- "+ Agregar carrera" permite elegir fecha y hora.

Todo se guarda solo en el teléfono (SQLite `bitacora.db`).

## Uso diario

1. Abre KMRentable: si ya está configurada, aparece de inmediato el aviso de Android para grabar la pantalla.
2. Elige **Pantalla completa** y toca **Empezar**. (Android lo pregunta en cada turno; no se puede guardar.)
3. Abre Uber, Cabify o DiDi y trabaja normal. Para terminar: **Terminar turno** en la notificación o en la app.

El panel queda en pantalla hasta que lo toques; si lo cierras, esa solicitud no vuelve a abrirlo.
Se mueve arrastrándolo. Suena un aviso (y vibra) cuando la carrera es rentable.

## Configuración (una sola vez)

La app muestra los pasos con ✅ cuando están listos:

1. **Mostrar sobre otras apps**: para dibujar el panel encima de la app de conducción.
2. **Notificaciones** (Android 13+): para ver "turno activo" y terminarlo desde ahí.
3. **Batería sin restricciones** y, en Xiaomi, **"Mostrar ventanas emergentes mientras se ejecuta en
   segundo plano"** (botón "Abrir permisos").

Ya no usa el servicio de accesibilidad: no hay "ajustes restringidos".

## Qué cuenta como solicitud

Solo se abre el panel con la tarjeta de una solicitud real:

- **Uber / DiDi**: botón para aceptar + tarifa arriba ("CLP9,518", "$1.847") + exactamente dos tramos
  (recogida y destino: "4 min • 1.6 km", "7 min (3,7 km)").
- **Cabify**: la tarjeta "$ 3.855 para ti" con sus dos tramos (lista general y solicitudes flotantes).
  Si hay varias en la lista, se muestra la de más arriba.

Pantallas de ganancias, de ir a buscar al pasajero, avisos o el mapa no abren el panel.
Con **"Solo carreras con tarjeta"** (por defecto), en Uber/DiDi el efectivo o un pago no visible
marcan **NO RENTABLE** con el motivo. En Cabify no se exige (ahí se desactiva el efectivo).

## Cómo lee la pantalla

Durante el turno se toma una captura cada segundo (MediaProjection) y se reconoce el texto con ML Kit,
sin internet. Se tapa el propio panel en la captura para no leerse a sí misma, y una solicitud tiene que
leerse igual en dos capturas seguidas antes de mostrarse.

## Instalar (pruebas)

Id. de la app: `cl.kmrentable.app`.
APK de cada versión en GitHub › Releases (`kmrentable-v1.N.apk`); la última siempre en
`releases/download/kmrentable-ultima/kmrentable.apk`. Para distribuir a conductores se usará la
prueba interna de Google Play.

## Si una solicitud no se detecta bien

En la app, sección "Si algo no funciona", toca **Copiar diagnóstico** con la solicitud en pantalla (o
justo después) y envíalo: incluye el texto que se reconoció en la captura.

## Estructura

```
carrera-rentable/
├── core/   Kotlin puro: lectura de la solicitud (OfferParser, ScreenFilter) y cálculo + pruebas
└── app/    Android: turno (captura + ML Kit), panel flotante, configuración guiada
```

## Compilar

JDK 17 y SDK de Android. `gradle :core:test :app:assembleDebug`.
GitHub Actions (`.github/workflows/carrera-rentable-apk.yml`) compila, prueba y publica cada versión.

### Firma
Todas las versiones se firman con la misma clave, guardada en la caché privada de GitHub Actions (no
está en el código). Si pasan más de 7 días sin compilar, GitHub puede borrarla y habrá que desinstalar
e instalar una vez. Para Google Play se usará una clave de subida guardada como secreto.

### Guardar las versiones en el PC
`powershell -ExecutionPolicy Bypass -File descargar-apks.ps1 -Carpeta C:\src\KmRentable` baja cada
versión nueva a `C:\src\KmRentable\APK`.
