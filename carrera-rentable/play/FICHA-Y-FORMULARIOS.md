# KMRentable · Google Play (prueba interna)

Textos para copiar y pegar en la Play Console. Ícono: `docs/play-icon-512.png`.

---

## 1. Crear la app

| Campo | Valor |
| --- | --- |
| Nombre | KMRentable |
| Idioma predeterminado | Español (Latinoamérica) – es-419 |
| App o juego | App |
| Gratis o pagada | Gratis |
| Id. del paquete | `cl.kmrentable.app` (lo toma del primer AAB que subas) |

## 2. Ficha de Play Store

**Nombre de la app (máx. 30):**
```
KMRentable: valor por km
```

**Descripción breve (máx. 80):**
```
Ve al instante cuánto paga por km cada solicitud de viaje y si te conviene.
```

**Descripción completa (máx. 4000):**
```
KMRentable es el asistente del conductor de apps de transporte.

Cuando te llega una solicitud de viaje, KMRentable lee la tarifa, los kilómetros y los minutos de la pantalla y te muestra un panel con:

• Valor por kilómetro (recogida + viaje)
• Pago por hora
• Kilómetros totales a manejar y minutos
• Veredicto claro: ✅ RENTABLE o ❌ NO RENTABLE según tu mínimo por km

Tú decides tus reglas: mínimo por km, mínimo por hora, tu costo por km (bencina y desgaste) y si solo quieres carreras pagadas con tarjeta.

CÓMO FUNCIONA
1. Abre KMRentable y empieza tu turno (Android te pide permiso para leer la pantalla).
2. Trabaja normal en tu app de conducción.
3. Cuando llega una solicitud, aparece el panel arriba de la pantalla. Tócalo o toca en cualquier parte para cerrarlo.

Suena un aviso cuando la carrera es rentable, para que lo sepas sin mirar el teléfono.

PRIVACIDAD
Todo se procesa en tu teléfono, sin internet. KMRentable no guarda ni envía capturas ni datos de tus viajes.

IMPORTANTE
KMRentable solo muestra información: no acepta ni rechaza viajes por ti. Es una app independiente, no está afiliada ni respaldada por ninguna plataforma de transporte. Funciona con apps como Uber, Cabify y DiDi.
```

**Categoría:** Auto y vehículos
**Correo de contacto:** soluciones.digitales.saec@gmail.com
**Política de privacidad (URL):** la dirección donde publiques `play/politica-privacidad.html` (ver punto 7).

**Gráficos**
- Ícono 512×512: `docs/play-icon-512.png`
- Imagen destacada 1024×500: el logo "KMRentable · El asistente del conductor" **sin** los logos de Uber, Cabify y DiDi.
- Capturas de teléfono (mínimo 2): pantalla de configuración con "✅ Listo para trabajar" y el panel de prueba.
  No uses capturas donde se vea la app de Uber/Cabify/DiDi ni sus logos.

## 3. Contenido de la app (Política › Contenido de la app)

**Política de privacidad:** URL del punto 7.

**Anuncios:** No, la app no contiene anuncios.

**Acceso a la app:** "Toda la funcionalidad está disponible sin restricciones" mientras no haya inicio de sesión.
Si después agregamos la verificación de suscripción, cambiar a "Algunas funciones están restringidas" y dar un correo de prueba activo.
Instrucciones para revisores:
```
La app no requiere cuenta. Para ver el panel sin una app de transporte:
1) Paso 1 de la configuración: activar "Mostrar sobre otras apps".
2) En la sección "Si algo no funciona", tocar "Mostrar panel de prueba".
El turno ("Empezar turno") pide el permiso de grabación de pantalla y lee las solicitudes de viaje
que aparecen en apps de conducción; sin esas apps no se muestra ningún panel.
```

**Clasificación de contenido (cuestionario IARC):** Categoría "Utilidades, productividad, comunicación u otro".
Responder **No** a violencia, sexo, lenguaje, drogas, apuestas, interacción entre usuarios, compras digitales y ubicación compartida.

**Público objetivo:** 18 años o más. No atrae a niños.

**Seguridad de los datos:**
- ¿Tu app recopila o comparte alguno de los tipos de datos del usuario requeridos? **No.**
  (Todo se procesa en el teléfono; nada se envía. Si agregamos la verificación de suscripción por correo,
  cambiar a "Sí" → Información personal › Dirección de correo electrónico → Recopilado, no compartido,
  obligatorio, propósito "Administración de la cuenta", cifrado en tránsito.)
- ¿Los datos se encriptan en tránsito? No aplica (no se envían datos).
- ¿Los usuarios pueden pedir que se borren sus datos? No aplica.

**Apps gubernamentales / financieras / de salud:** No.

**Servicios en primer plano (Foreground service)**
Tipo: **Proyección de medios (mediaProjection)**. Descripción:
```
Cuando el conductor toca "Empezar turno", la app inicia un servicio en primer plano que usa
MediaProjection para capturar la pantalla una vez por segundo y reconocer en el propio
teléfono (ML Kit, sin internet) el texto de las solicitudes de viaje: tarifa, kilómetros y
minutos. Con eso muestra un panel con el valor por kilómetro. El servicio corre solo mientras
el turno está activo, muestra una notificación permanente con el botón "Terminar turno" y
Android muestra su indicador de grabación. Las capturas no se guardan ni se envían.
```
Impacto si se interrumpe: "El conductor deja de ver el valor por km de las solicitudes".
Google puede pedir un **video corto** (YouTube no listado) mostrando: empezar turno → aceptar el permiso → el panel apareciendo → terminar turno desde la notificación. Grábalo con una solicitud de prueba o con el panel de prueba.

**Permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW):** no requiere formulario en Play; se explica en la descripción.

## 4. Prueba interna

1. Pruebas › **Prueba interna** › Crear versión.
2. **Integridad de la app:** aceptar que **Google Play firme la app** (firma de apps de Google Play).
3. Subir el archivo **`kmrentable-v1.N.aab`** (GitHub › Actions › última ejecución › Artifacts, ver `CLAVE-DE-SUBIDA.md`).
4. Notas de la versión: "Primera versión de prueba".
5. Pestaña **Testers** › crear lista con los correos (máx. 100) › copiar el **enlace para unirse** y enviárselo a los conductores.

Los conductores abren el enlace con su cuenta de Google, aceptan ser testers e instalan desde Play.

## 5. Antes de instalar desde Play

Quien tenga instalada la versión de GitHub (APK) debe **desinstalarla**: la firma de Play es distinta.

## 6. Cobro mensual

La app de Play es gratis; el cobro se hace por fuera y el acceso se controla con la lista de testers y,
más adelante, con la verificación de suscripción (correo + fecha de vencimiento en una planilla).

## 7. Publicar la política de privacidad

Google necesita una URL pública. Opciones:
- Donde publicaste la de LeadAlert (misma web o mismo GitHub Pages): sube `politica-privacidad.html`.
- Un repositorio público nuevo (por ejemplo `kmrentable`) con GitHub Pages activado y este archivo como `index.html`.
- Google Sites: crear una página y pegar el texto.
