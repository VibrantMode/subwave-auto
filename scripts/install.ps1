# Install the SUB/WAVE Auto release APK onto the one connected phone.
# Usage: scripts\install.ps1 [path\to.apk]   (default: the assembleRelease output)
param([string]$Apk = "")

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$adb = "C:\Users\Mike\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

if ($Apk -eq "") { $Apk = "app\build\outputs\apk\release\app-release.apk" }
if (-not (Test-Path $Apk)) {
    Write-Error "APK not found: $Apk  (run scripts\build-release.ps1 first, or pass an APK path)"
    exit 1
}

# Exactly one authorized device, or bail with what we saw.
$raw = & $adb devices
$devices = @($raw | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] })
if ($devices.Count -ne 1) {
    Write-Output "ERROR: need exactly ONE authorized device, found $($devices.Count)."
    Write-Output "adb devices output:"
    $raw | ForEach-Object { Write-Output $_ }
    Write-Output "(unauthorized = accept the USB-debugging prompt on the phone)"
    exit 1
}

Write-Output "Installing $Apk onto $($devices[0]) ..."
& $adb install -r $Apk
exit $LASTEXITCODE
