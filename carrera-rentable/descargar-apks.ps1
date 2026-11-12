# Crea la carpeta del proyecto en este PC y descarga ahí todas las versiones del APK.
# Uso (PowerShell):  powershell -ExecutionPolicy Bypass -File descargar-apks.ps1
# Puedes volver a ejecutarlo cuando quieras: solo baja las versiones nuevas.
param(
    [string]$Carpeta = (Join-Path ([Environment]::GetFolderPath("MyDocuments")) "KMRentable")
)

$apk = Join-Path $Carpeta "APK"
New-Item -ItemType Directory -Force -Path $apk | Out-Null

$releases = Invoke-RestMethod "https://api.github.com/repos/solucionesdigitalessaec-sudo/HCC/releases?per_page=100"
$nuevas = 0
foreach ($r in $releases) {
    foreach ($a in $r.assets) {
        if ($a.name -notmatch '^(kmrentable|carrera-rentable)-v[\d.]+\.apk$') { continue }
        $destino = Join-Path $apk $a.name
        if (Test-Path $destino) { continue }
        Write-Host "Descargando $($a.name)..."
        Invoke-WebRequest $a.browser_download_url -OutFile $destino
        $nuevas++
    }
}
Write-Host ""
Write-Host "Listo: $nuevas versión(es) nueva(s) en $apk"
Get-ChildItem $apk -Filter *.apk | Sort-Object LastWriteTime | Format-Table Name, Length, LastWriteTime
