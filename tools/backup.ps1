# Snapshot the TV before changing anything: which packages are enabled, every setting, and the
# current home app. restore.ps1 puts the TV back from exactly these files. Run it first, once.
param(
    [string]$Tv = $env:AURA_TV,
    [switch]$Force   # overwrite an existing backup (normally you want to keep the first one)
)
$ErrorActionPreference = "Stop"
if (-not $Tv) { throw "Pass the TV's address, e.g. -Tv 192.168.1.50:5555, or set `$env:AURA_TV." }
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$backup = Join-Path (Split-Path -Parent $PSScriptRoot) "backup"
if ((Test-Path "$backup\packages-enabled.txt") -and -not $Force) {
    throw "A backup already exists in $backup. It's the state to restore to, so it's kept; use -Force to replace it."
}
New-Item -ItemType Directory -Force $backup | Out-Null

& $adb connect $Tv | Out-Null
if (((& $adb -s $Tv get-state 2>&1) -join '') -notmatch 'device') { throw "TV $Tv not reachable over ADB" }

function Save([string]$file, [string[]]$cmd) {
    $text = (& $adb -s $Tv shell @cmd) -join "`n"
    [System.IO.File]::WriteAllText("$backup\$file", $text + "`n", (New-Object System.Text.UTF8Encoding($false)))
    "saved $file"
}

Save "packages-enabled.txt" @('pm', 'list', 'packages', '-e')
Save "packages-disabled.txt" @('pm', 'list', 'packages', '-d')
Save "settings-global.txt" @('settings', 'list', 'global')
Save "settings-secure.txt" @('settings', 'list', 'secure')
Save "settings-system.txt" @('settings', 'list', 'system')
Save "home-activity.txt" @('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
Save "getprop.txt" @('getprop')
[System.IO.File]::WriteAllText("$backup\TIMESTAMP.txt", (Get-Date -Format o) + "`n")
"Backup written to $backup. Keep it private: it lists your apps and settings."
