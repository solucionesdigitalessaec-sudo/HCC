# KMRentable · app Android para conductores

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

Cuando la carrera es **rentable suena un aviso** (tres tonos) para que lo sepas sin mirar el
teléfono; las no rentables no suenan. Se puede apagar y probar en la pantalla de ajustes. Usa el
volumen de notificaciones, así que respeta el modo silencio.

Ejemplo (la solicitud de Uber de la captura): $6.980 · 9 min (3,6 km) + 22 min (14 km)
→ 17,6 km, 31 min → **$397/km**, $13.510/h → **NO RENTABLE** (le faltan $5.340 para llegar a $700/km).

La app **solo lee**: no acepta ni rechaza viajes por ti (hacerlo de forma automática puede
violar los términos de las plataformas). Nada sale del teléfono; no usa internet.

## Instalar

1. En GitHub, abre **Releases › KMRentable (última versión)** y descarga `kmrentable.apk`
   desde el teléfono (o en **Actions › APK KMRentable ›** última ejecución › *Artifacts*).
2. Ábrelo y permite "instalar apps de origen desconocido".
   - Si **Google Play Protect** dice "Se bloqueó la app": Play Store › tu foto › Play Protect › ⚙️ ›
     apaga *Analizar apps con Play Protect*, instala y vuelve a encenderlo. Pasa porque la app usa
     el servicio de accesibilidad para leer la pantalla.
3. Abre **KMRentable** › *Abrir ajustes de accesibilidad* › activa **KMRentable**.
   - En Android 13+ puede salir **"Ajuste restringido"**. Ve a *Ajustes › Apps › KMRentable ›
     menú ⋮ › Permitir ajustes restringidos* (botón "Abrir info de la app") y vuelve a activarlo.
4. Ajusta tus reglas (mínimo $/km, $/h, costo por km) y abre Uber/DiDi/Cabify.

El panel se cierra tocándolo, se mueve arrastrándolo y se oculta solo tras 20 s (configurable).

## Guardar todas las versiones en tu PC

Cada vez que se cambia algo, GitHub construye una versión nueva (**v1.4, v1.5, ...**) y la guarda
en *Releases*. La versión aparece arriba en la pantalla de la app.

Para tenerlas todas en tu PC, en PowerShell, dentro de esta carpeta:

```powershell
powershell -ExecutionPolicy Bypass -File descargar-apks.ps1
```

Crea `Documentos\KMRentable\APK` y baja ahí cada versión (`kmrentable-v1.6.apk`, ...).
Si lo vuelves a ejecutar, solo baja las nuevas.

## Firma de la app

Todas las versiones se firman con la misma clave para poder instalarlas encima de la anterior.
La clave se crea una vez en GitHub Actions y se guarda en su **caché privada** (no está en el código).
Si pasan más de 7 días sin compilar, GitHub puede borrar esa caché: se crea una clave nueva y hay que
desinstalar e instalar una vez. Lo mismo pasa la primera vez que se compile en otra rama (por ejemplo `main`).

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
