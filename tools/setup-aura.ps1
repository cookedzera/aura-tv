# (Re)install Aura on the TV and make it the home screen and screensaver.
# Use after a factory reset or if Android ever hands Home back to Google TV.
param(
    [string]$Tv = $env:AURA_TV,
    [switch]$DisableGoogleHome   # also retire the Google TV home + its setup fallback (reversible)
if (-not $Tv) { throw "Pass the TV's address, e.g. -Tv 192.168.1.50:5555, or set `$env:AURA_TV." }
)
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "launcher\Aura.apk"

& $adb connect $Tv | Out-Null
if (((& $adb -s $Tv get-state 2>&1) -join '') -notmatch 'device') { throw "TV $Tv not reachable over ADB" }

& $adb -s $Tv install -r $apk
& $adb -s $Tv shell cmd package set-home-activity app.aura.home/.HomeActivity
# force-lock only: lets the Standby button and sleep timer turn the TV off
& $adb -s $Tv shell dpm set-active-admin --user 0 app.aura.home/.SleepAdmin
# usage history for Habit Stage (read on the TV only) and a full ahead-of-time compile for speed
& $adb -s $Tv shell appops set app.aura.home GET_USAGE_STATS allow
& $adb -s $Tv shell cmd package compile -m speed -f app.aura.home
& $adb -s $Tv shell settings put secure screensaver_components app.aura.home/.Ambient
& $adb -s $Tv shell settings put secure screensaver_default_component app.aura.home/.Ambient
if ($DisableGoogleHome) {
    & (Join-Path $PSScriptRoot "debloat.ps1") -Tv $Tv -IncludeGoogleTvHome
    & $adb -s $Tv shell cmd package set-home-activity app.aura.home/.HomeActivity
}
& $adb -s $Tv shell input keyevent KEYCODE_HOME
"Aura is set up. Home resolves to: " + ((& $adb -s $Tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) | Select-Object -Last 1)
