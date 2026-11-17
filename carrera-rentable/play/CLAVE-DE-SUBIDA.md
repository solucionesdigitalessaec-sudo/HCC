# Clave de subida para Google Play

Google Play firma la app final con **su propia clave** ("Firma de apps de Google Play").
Nosotros solo necesitamos una **clave de subida** que identifica que los archivos que subimos son nuestros.
Se crea **una sola vez**, en tu PC, y se guarda como **secreto** en GitHub: no queda en el código ni en el chat.

> Si la clave de subida se pierde, se puede pedir a Google que la cambie (Play Console › Integridad de la app),
> pero tarda unos días. Por eso: **haz un respaldo**.

## Paso 1 · Crear la clave en tu PC

Opción A (lo más simple): en PowerShell, dentro de la carpeta del proyecto:
```powershell
powershell -ExecutionPolicy Bypass -File carrera-rentable\play\crear-clave-subida.ps1
```
Opción B: pídele a tu Claude Code de escritorio (modo local):
> Ejecuta el script carrera-rentable/play/crear-clave-subida.ps1 del repositorio HCC (rama ccr-ca89b792-6yyujw).
> Si falta Java, instálalo. No me muestres la contraseña en el chat.

El script deja en `C:\src\KmRentable\firma\` el archivo `kmrentable-upload.jks` y su contraseña en
`kmrentable-upload-pass.txt`, y copia al portapapeles el texto para GitHub.

## Paso 2 · Guardarla como secreto en GitHub

En GitHub, repositorio **HCC** › **Settings** › **Secrets and variables** › **Actions** › **New repository secret**:

| Nombre | Valor |
| --- | --- |
| `KMR_UPLOAD_KEYSTORE_B64` | lo que el script dejó en el portapapeles (texto largo) |
| `KMR_UPLOAD_PASS` | el contenido de `kmrentable-upload-pass.txt` |

## Paso 3 · Respaldo

Copia la carpeta `C:\src\KmRentable\firma\` a un lugar seguro y privado (pendrive, Google Drive personal).
**No** la subas al repositorio ni la compartas.

## Paso 4 · Obtener el AAB

Con los secretos guardados, cada cambio en GitHub genera además el archivo para Play:
**Actions** › última ejecución de "APK KMRentable" › **Artifacts** › `kmrentable-v1.N-google-play`
(descarga un .zip con el `.aab` adentro). Ese `.aab` es el que se sube en Play Console › Prueba interna.

Para generar uno sin cambiar código: Actions › "APK KMRentable" › **Run workflow**.
