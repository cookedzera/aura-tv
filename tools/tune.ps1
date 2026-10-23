# Speed tuning that Android supports on this TV but no settings screen exposes. All reversible
# (restore.ps1 undoes it). Nothing here bypasses security: these are standard developer controls.
param(
    [string]$Tv = $env:AURA_TV,
    [switch]$Undo
)
if (-not $Tv) { throw "Pass the TV's address, e.g. -Tv 192.168.1.50:5555, or set `$env:AURA_TV." }
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

# streaming apps whose background jobs only fed the (now disabled) Google TV home rows;
# Netflix and Prime are left alone because the manufacturer exempts them
$quiet = 'in.startv.hotstar', 'com.sonyliv', 'app.netmirror.newtv', 'io.gh.reisxd.tizentube.cobalt', 'com.google.android.youtube.tv'

& $adb connect $Tv | Out-Null
if ($Undo) {
    # also clears the freezer flag an earlier version of this script set
    & $adb -s $Tv shell settings delete global cached_apps_freezer
    foreach ($p in $quiet) { & $adb -s $Tv shell cmd appops set $p RUN_ANY_IN_BACKGROUND default }
    & $adb -s $Tv shell appops set app.aura.home GET_USAGE_STATS default
    "Tuning undone."
    return
}

# Not here: Android 11's cached-app freezer. This TV's 4.14 kernel lacks the freezer.killable
# cgroup file Android 11 requires, so Android keeps the freezer off whatever the setting says
# (dumpsys activity shows use_freezer=false even with cached_apps_freezer=enabled after a reboot).

# 1. background restriction: the apps work exactly the same when open
foreach ($p in $quiet) { & $adb -s $Tv shell cmd appops set $p RUN_ANY_IN_BACKGROUND ignore }

# 2. Aura: usage history for For You and Today (stays on the TV), and a full ahead-of-time compile
& $adb -s $Tv shell appops set app.aura.home GET_USAGE_STATS allow
& $adb -s $Tv shell cmd package compile -m speed -f app.aura.home

"Tuned."
