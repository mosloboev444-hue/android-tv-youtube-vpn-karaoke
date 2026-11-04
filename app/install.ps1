# ============================================================================
#  YouTube VPN Gate - install on an Android TV
#
#  Installs the APK, enables the AccessibilityService over adb (no remote
#  needed) and prints the accessibility state for a quick check.
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File install.ps1 -TvHost 192.168.1.50
#    powershell -ExecutionPolicy Bypass -File install.ps1 -TvHost 192.168.1.50 -Adb C:\adb\adb.exe
# ============================================================================
param(
    [Parameter(Mandatory = $true)]
    [string]$TvHost,
    [string]$Adb = 'adb',
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Apk) {
    $built = Join-Path $root 'YouTube-VPN-Gate.apk'
    $shipped = Join-Path (Split-Path $root -Parent) 'apk\YouTube-VPN-Gate-1.0.apk'
    $Apk = if (Test-Path $built) { $built } elseif (Test-Path $shipped) { $shipped } else { $built }
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (run build.ps1 first)" }

$adbCmd = Get-Command $Adb -ErrorAction SilentlyContinue
if (-not $adbCmd) { throw "adb not found: $Adb (Android platform-tools)" }
$Adb = $adbCmd.Source

$component = 'com.gathin.vpngate/com.gathin.vpngate.GateAccessibilityService'
$device = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }

Write-Host "ADB: $Adb"
& $Adb connect $device | Out-Null
Start-Sleep -Milliseconds 500
& $Adb -s $device wait-for-device
if ($LASTEXITCODE -ne 0) { throw "TV $device is not reachable" }

Write-Host '1) Install APK'
& $Adb -s $device install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'install failed' }

Write-Host '2) Enable AccessibilityService'
$current = (& $Adb -s $device shell "settings get secure enabled_accessibility_services").Trim()
if ($current -eq 'null' -or $current -eq '') { $current = '' }
if ($current -notlike "*$component*") {
    $next = if ($current) { "$current`:$component" } else { $component }
    & $Adb -s $device shell "settings put secure enabled_accessibility_services '$next'"
} else {
    Write-Host '   already enabled'
}
& $Adb -s $device shell 'settings put secure accessibility_enabled 1'

Start-Sleep -Seconds 2

Write-Host '3) Check'
& $Adb -s $device shell 'dumpsys accessibility' | Select-String 'vpngate|enabledServices' | Select-Object -First 4

Write-Host ''
Write-Host 'Done. Now:'
Write-Host '  1. Open the VPN app (Uboo), log in and pick the "YouTube only" mode.'
Write-Host '  2. Disconnect the VPN.'
Write-Host '  3. Open YouTube: the gate opens the VPN app, presses Connect and comes back.'
Write-Host "  4. Logs: $Adb -s $device logcat -s VpnGate"
