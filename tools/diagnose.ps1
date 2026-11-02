# Measure Aura on the TV and write it all to one text file, to send with a problem report:
# how long Aura takes to start from scratch, how smoothly it draws while you use it, how much
# memory it holds, whether Android has compiled it, and anything it noted going wrong.
# Nothing on the TV is changed; Aura is only stopped and started again to time its starts.
param(
    [string]$Tv = $env:AURA_TV,
    [int]$Starts = 3,      # starts from scratch to time
    [switch]$NoFrames      # skip the part where you use the remote for half a minute
)
if (-not $Tv) { throw "Pass the TV's address, e.g. -Tv 192.168.1.50:5555, or set `$env:AURA_TV." }
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$pkg = "app.aura.home"

& $adb connect $Tv | Out-Null
if (((& $adb -s $Tv get-state 2>&1) -join '') -notmatch 'device') { throw "TV $Tv not reachable over ADB" }

$lines = New-Object System.Collections.Generic.List[string]
function Section([string]$title) {
    $lines.Add("")
    $lines.Add("== $title")
    Write-Host "== $title"
}
function Shell([string]$cmd) {
    $ErrorActionPreference = "Continue"
    $r = & $adb -s $Tv shell $cmd 2>&1
    foreach ($l in $r) { $lines.Add("$l") }
    return $r
}

Section "TV"
Shell "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.display.id" | Out-Null
Shell "dumpsys package $pkg | grep -E 'versionName|versionCode|lastUpdateTime' | head -3" | Out-Null
Shell "cat /proc/meminfo | head -3; uptime" | Out-Null

Section "Compiled ahead of time? (speed or speed-profile is good; verify or quicken means interpreted)"
Shell "dumpsys package dexopt | grep -A 3 '\[$pkg\]' | head -6" | Out-Null

Section "Starts from scratch (am start -W: until Aura's first frame)"
$times = @()
for ($i = 1; $i -le $Starts; $i++) {
    # -S stops Aura and starts it again in one go: Android relaunches a stopped home screen by
    # itself at once, which a separate stop would race with
    $r = Shell "am start -W -S -a android.intent.action.MAIN -c android.intent.category.HOME -n $pkg/.HomeActivity | grep -E 'LaunchState|TotalTime|WaitTime|Status'"
    $total = ($r | Select-String -Pattern 'TotalTime:\s*(\d+)').Matches | ForEach-Object { [int]$_.Groups[1].Value }
    if ($total) { $times += $total; Write-Host "  start $i : $total ms to first frame" }
    # the boot screen, the photograph and the apps: give it time to arrive before the next one
    Start-Sleep -Seconds 10
}
if ($times.Count -gt 0) { $lines.Add("first frame, average of $($times.Count): " + [int](($times | Measure-Object -Average).Average) + " ms") }

Section "Aura's own start times (until home was on show)"
Shell "logcat -d -s Aura:I | grep 'start:' | tail -5" | Out-Null

if (-not $NoFrames) {
    Section "Frames while you use it"
    & $adb -s $Tv shell dumpsys gfxinfo $pkg reset | Out-Null
    Write-Host ""
    Write-Host "Now use the remote on the TV for about 30 seconds: move around the apps, flip the Smart Stack,"
    Write-Host "open Control Center (Menu) and close it, press Home twice. Then come back here."
    Read-Host "Press Enter when you're done" | Out-Null
    Shell "dumpsys gfxinfo $pkg | grep -E 'Total frames|Janky frames|percentile|Number Missed|Slow UI|Slow bitmap|Slow issue|Frame deadline'" | Out-Null
}

Section "Which screens answer Home and the settings links Aura uses (resolve-activity, Android 7+)"
foreach ($a in @("android.intent.action.MAIN -c android.intent.category.HOME", "android.settings.SETTINGS",
        "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS", "android.settings.DISPLAY_SETTINGS",
        "android.settings.SOUND_SETTINGS", "android.settings.DREAM_SETTINGS", "android.settings.ACCESSIBILITY_SETTINGS",
        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
        "android.settings.HOME_SETTINGS")) {
    $lines.Add("-- $a")
    Shell "cmd package resolve-activity --brief -a $a | tail -1" | Out-Null
}

Section "Aura's services switched on (Home button, Now playing, screensaver)"
Shell "settings get secure enabled_accessibility_services; settings get secure enabled_notification_listeners; settings get secure screensaver_components; settings get secure screensaver_enabled" | Out-Null

Section "The TV's own home app: its screens (Amazon's or Google's launcher, if present)"
Shell "for p in com.amazon.tv.launcher com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do pm path `$p >/dev/null 2>&1 && dumpsys package `$p | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+Activity[a-zA-Z0-9_]*' | sort -u | head -40; done" | Out-Null

Section "Memory"
Shell "dumpsys meminfo $pkg | grep -E 'TOTAL|Java Heap|Native Heap|Graphics|Private Other|Views:|Activities:' | head -12" | Out-Null

Section "What Aura noted going wrong (newest last)"
Shell "logcat -d -s Aura:W | tail -60" | Out-Null

Section "Crashes Android logged for Aura"
Shell "logcat -d -b crash | grep -A 25 'Process: $pkg' | tail -80" | Out-Null

$out = Join-Path (Split-Path -Parent $PSScriptRoot) ("diagnose-" + (Get-Date -Format "yyyyMMdd-HHmm") + ".txt")
[System.IO.File]::WriteAllText($out, ($lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))
Write-Host ""
Write-Host "Written to $out"
