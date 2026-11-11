# Carrera Rentable · app Android para conductores

Cuando te llega una solicitud en **Uber Driver, DiDi Conductor o Cabify Driver**, la app lee la
tarifa, los kilómetros y los minutos de la pantalla y muestra un panel flotante con:

| Dato          | Cómo se calcula                                           |
| ------------- | --------------------------------------------------------- |
| **Valor km**  | tarifa ÷ km totales (recogida + viaje)                    |
| **Por hora**  | tarifa ÷ minutos totales × 60                             |
| **Pagan**     | tarifa de la solicitud                                    |
| **Km a manejar** | km de recogida + km del viaje                          |
| **Minutos**   | minutos de recogida + minutos del viaje                   |
| **Faltan / Te queda** | cuánto faltaría para llegar al mínimo, o la ganancia neta si configuras tu costo por km |

Arriba aparece **✅ RENTABLE** (verde) o **❌ NO RENTABLE** (rojo). Por defecto una carrera es
rentable si paga **$700 o más por km**. Puedes cambiar el mínimo y agregar un mínimo por hora.

Ejemplo (la solicitud de Uber de la captura): $6.980 · 9 min (3,6 km) + 22 min (14 km)
→ 17,6 km, 31 min → **$397/km**, $13.510/h → **NO RENTABLE** (le faltan $5.340 para llegar a $700/km).

La app **solo lee**: no acepta ni rechaza viajes por ti (hacerlo de forma automática puede
violar los términos de las plataformas). Nada sale del teléfono; no usa internet.

## Instalar

1. En GitHub, abre **Releases › Carrera Rentable (última versión)** y descarga `carrera-rentable.apk`
   desde el teléfono (o en **Actions › APK Carrera Rentable ›** última ejecución › *Artifacts*).
2. Ábrelo y permite "instalar apps de origen desconocido".
3. Abre **Carrera Rentable** › *Abrir ajustes de accesibilidad* › activa **Carrera Rentable**.
   - En Android 13+ puede salir **"Ajuste restringido"**. Ve a *Ajustes › Apps › Carrera Rentable ›
     menú ⋮ › Permitir ajustes restringidos* (botón "Abrir info de la app") y vuelve a activarlo.
4. Ajusta tus reglas (mínimo $/km, $/h, costo por km) y abre Uber/DiDi/Cabify.

El panel se cierra tocándolo, se mueve arrastrándolo y se oculta solo tras 20 s (configurable).

## Si una solicitud no se detecta bien

Cada app muestra los datos con formato distinto y lo cambian seguido. En la app, sección 4,
toca **"Copiar último texto leído"** y pega ese texto; con eso se agrega el formato a
`core/src/main/kotlin/.../OfferParser.kt` y a sus pruebas.

## Estructura

```
carrera-rentable/
├── core/   Kotlin puro: lectura del texto (OfferParser) y cálculo (ProfitCalculator) + pruebas
└── app/    Android: servicio de accesibilidad, panel flotante y pantalla de ajustes
```

## Compilar en tu computador

Requiere JDK 17 y el SDK de Android (Android Studio lo instala). Abre la carpeta `carrera-rentable`
en Android Studio, o desde la terminal:

```bash
gradle :core:test :app:assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions (`.github/workflows/carrera-rentable-apk.yml`) hace esto en cada cambio dentro de
esta carpeta y publica el APK.
