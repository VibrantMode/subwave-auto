# Build the SUB/WAVE Auto release APK.
# Usage: scripts\build-release.ps1
$ErrorActionPreference = "Stop"

Set-Location (Join-Path $PSScriptRoot "..")

# Android Studio's bundled JBR (JDK 21) — same JDK Studio builds with.
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

& .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path (Get-Location) "app\build\outputs\apk\release\app-release.apk"
Write-Output ""
Write-Output "APK: $apk"
if (Test-Path "keystore.properties") {
    Write-Output "Signing: RELEASE key (keystore.properties found)"
} else {
    Write-Output "Signing: DEBUG key fallback (keystore.properties missing - see docs/SIGNING.md)"
}
