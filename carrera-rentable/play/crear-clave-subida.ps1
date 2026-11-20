# Crea la CLAVE DE SUBIDA de KMRentable para Google Play, en este PC.
# Uso (PowerShell):  powershell -ExecutionPolicy Bypass -File crear-clave-subida.ps1
#
# Deja en C:\src\KmRentable\firma\:
#   kmrentable-upload.jks       la clave (¡guárdala con respaldo! sin ella no se puede subir a Play)
#   kmrentable-upload-pass.txt  su contraseña
# y copia al portapapeles lo que hay que pegar en GitHub (ver CLAVE-DE-SUBIDA.md).
param([string]$Carpeta = "C:\src\KmRentable\firma")

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $Carpeta | Out-Null
$jks = Join-Path $Carpeta "kmrentable-upload.jks"
$passFile = Join-Path $Carpeta "kmrentable-upload-pass.txt"
if (Test-Path $jks) { Write-Host "Ya existe $jks. No se crea otra (no la reemplaces)."; exit 1 }

# Buscar keytool: JAVA_HOME, Android Studio o PATH
$candidatos = @()
if ($env:JAVA_HOME) { $candidatos += Join-Path $env:JAVA_HOME "bin\keytool.exe" }
$candidatos += "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$candidatos += "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe"
$enPath = Get-Command keytool -ErrorAction SilentlyContinue
if ($enPath) { $candidatos += $enPath.Source }
$keytool = $candidatos | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $keytool) {
    Write-Host "No encontré keytool. Instala Java (por ejemplo Temurin 17 desde adoptium.net) o Android Studio y vuelve a ejecutar."
    exit 1
}

# Contraseña aleatoria (solo letras y números para evitar problemas al copiarla)
$chars = (48..57) + (65..90) + (97..122)
$pass = -join (1..32 | ForEach-Object { [char]($chars | Get-Random) })

& $keytool -genkeypair -noprompt -keystore $jks -storepass $pass -keypass $pass `
    -alias kmrentable-upload -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=KMRentable, O=Soluciones Digitales, C=CL"
Set-Content -Path $passFile -Value $pass -NoNewline

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
Set-Clipboard -Value $b64

Write-Host ""
Write-Host "Clave creada en: $jks"
Write-Host "Contraseña en:   $passFile"
Write-Host ""
Write-Host "1) Ya copié al portapapeles el valor para el secreto KMR_UPLOAD_KEYSTORE_B64. Pégalo en GitHub."
Write-Host "2) Después crea el secreto KMR_UPLOAD_PASS con el contenido de kmrentable-upload-pass.txt."
Write-Host "3) Haz una copia de respaldo de la carpeta $Carpeta (pendrive, Google Drive privado...)."
