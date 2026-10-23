# Debloat the Acer/Dixon Google TV (DIX41RKF) over ADB.
# Every change is `pm disable-user` -- nothing is uninstalled. Undo with restore.ps1.
param(
    [string]$Tv = $env:AURA_TV,
    [switch]$IncludeGoogleTvHome   # only after the new launcher is installed and set as home
)
if (-not $Tv) { throw "Pass the TV's address, e.g. -Tv 192.168.1.50:5555, or set `$env:AURA_TV." }

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path -Parent $PSScriptRoot
$log = Join-Path $root "backup\debloat-log.txt"

$targets = [ordered]@{
    # Ads, sponsored rows and auto-installed promos
    "android.autoinstalls.config.rplus.device"      = "Play Auto Installs: pushes promoted apps"
    "com.realtek.leanbacklauncher.partnercustomizer" = "Partner launcher customizer: sponsored rows"
    "com.vewd.core.service"                         = "Vewd (Opera TV) app store / ad service"
    "com.google.android.partnersetup"               = "Partner setup / promo client IDs"
    # Telemetry and setup leftovers
    "com.google.android.feedback"                   = "Google Feedback: crash & usage reports"
    "com.google.android.onetimeinitializer"         = "One-time setup initializer"
    "com.android.printspooler"                      = "Print spooler (no printing on a TV)"
    "com.google.android.syncadapters.calendar"      = "Calendar sync"
    # Factory / test / demo tools
    "com.dixon.tvfactory"                           = "Dixon factory test app"
    "com.kgzn.serialport"                           = "KGZN factory auto-test (was running constantly)"
    "com.rtk.engmenu"                               = "Realtek engineer menu"
    "com.realtek.factorymenu"                       = "Realtek factory menu"
    "com.realtek.retailmode"                        = "Store demo / retail mode"
    # Antenna tuners (you watch via HDMI; HDMI passthrough stays enabled)
    "com.realtek.dtmb"                              = "DTMB tuner (China)"
    "com.realtek.tv.atsc"                           = "ATSC tuner (USA)"
    "com.realtek.tv.isdb"                           = "ISDB tuner (Japan/Brazil)"
    "com.realtek.tv.atv"                            = "Analog antenna/cable tuner"
    "com.realtek.dtv"                               = "Digital antenna/cable tuner"
    # Features you said you don't use
    "com.google.android.apps.mediashell"            = "Chromecast built-in (casting)"
    "com.google.android.tv.remote.service"          = "Phone-as-remote service"
    "com.google.android.apps.tv.dreamx"             = "Google ambient screensaver (replaced by Aura's)"
    "com.google.android.youtube.tvmusic"            = "YouTube Music"
    "com.google.android.videos"                     = "Google TV store (rent/buy, home-screen promos)"
    "com.google.android.play.games"                 = "Play Games"
}
if ($IncludeGoogleTvHome) {
    $targets["com.google.android.apps.tv.launcherx"] = "Google TV home screen (ads, ~150 MB RAM)"
    # its setup app registers a higher-priority "recovery" Home that would otherwise hijack the Home button
    $targets["com.google.android.tungsten.setupwraith"] = "Google TV setup (only runs on first boot; owns a fallback Home)"
}

$disabled = (& $adb -s $Tv shell pm list packages -d) -replace '^package:', ''
"=== debloat run $(Get-Date -Format o) ===" | Out-File -Append -Encoding utf8 $log

foreach ($pkg in $targets.Keys) {
    if ($disabled -contains $pkg) {
        $line = "SKIP  $pkg (already disabled)"
    } else {
        & $adb -s $Tv shell am force-stop $pkg 2>&1 | Out-Null
        $out = (& $adb -s $Tv shell pm disable-user --user 0 $pkg 2>&1) -join ' '
        $status = if ($out -match 'new state: disabled-user') { "OK   " } else { "FAIL " }
        $line = "$status $pkg -- $($targets[$pkg]) [$out]"
    }
    $line
    $line | Out-File -Append -Encoding utf8 $log
}
