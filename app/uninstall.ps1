# ============================================================================
#  YouTube VPN Gate - remove from an Android TV
#
#  Turns the accessibility service off, uninstalls com.gathin.vpngate and
#  removes leftover always-on VPN settings.
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File uninstall.ps1 -TvHost 192.168.1.50
# ============================================================================
param(
    [Parameter(Mandatory = $true)]
    [string]$TvHost,
    [string]$Adb = 'adb'
)

$ErrorActionPreference = 'Stop'

$adbCmd = Get-Command $Adb -ErrorAction SilentlyContinue
if (-not $adbCmd) { throw "adb not found: $Adb (Android platform-tools)" }
$Adb = $adbCmd.Source

$component = 'com.gathin.vpngate/com.gathin.vpngate.GateAccessibilityService'
$device = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }

& $Adb connect $device | Out-Null
Start-Sleep -Milliseconds 500
& $Adb -s $device wait-for-device

Write-Host '1) Disable the accessibility service'
$current = (& $Adb -s $device shell "settings get secure enabled_accessibility_services").Trim()
if ($current -and $current -ne 'null') {
    $remaining = @($current -split ':' | Where-Object { $_ -and $_ -ne $component })
    $next = $remaining -join ':'
    & $Adb -s $device shell "settings put secure enabled_accessibility_services '$next'"
    if (-not $next) { & $Adb -s $device shell 'settings put secure accessibility_enabled 0' }
} else {
    & $Adb -s $device shell 'settings put secure accessibility_enabled 0'
}

Write-Host '2) Remove always-on VPN leftovers'
& $Adb -s $device shell 'settings delete secure always_on_vpn_app' | Out-Null
& $Adb -s $device shell 'settings delete secure always_on_vpn_lockdown' | Out-Null

Write-Host '3) Uninstall the gate'
& $Adb -s $device uninstall com.gathin.vpngate

Write-Host 'Done.'
