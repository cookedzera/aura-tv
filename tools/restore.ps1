# Put the TV back exactly as it was before Aura and the debloat, using the backup taken first.
#   - re-enables every package that was enabled at backup time (nothing was ever uninstalled)
#   - restores the screensaver and animation settings that were changed
#   - releases Aura's standby admin, hands Home back to Google TV
#   - with -RemoveAura, also uninstalls Aura
# Run with -DryRun first to see every command without changing anything.
param(
    [string]$Tv = $env:AURA_TV,
    [switch]$RemoveAura,
    [switch]$DryRun
)
if (-not $Tv) { throw "Pass the TV's address, e.g. -Tv 192.168.1.50:5555, or set `$env:AURA_TV." }
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$backup = Join-Path (Split-Path -Parent $PSScriptRoot) "backup"
if (-not (Test-Path "$backup\packages-enabled.txt")) { throw "Backup not found in $backup" }

function Shell([string[]]$cmd) {
    "> adb shell $($cmd -join ' ')"
    if (-not $DryRun) {
        $ErrorActionPreference = "Continue"
        & $adb -s $Tv shell @cmd 2>&1 | ForEach-Object { "    $_" }
    }
}

function BackedUp([string]$file, [string]$key) {
    $line = Get-Content "$backup\$file" | Where-Object { $_ -like "$key=*" } | Select-Object -First 1
    if ($line) { $line.Substring($key.Length + 1) } else { $null }
}

& $adb connect $Tv | Out-Null
if (((& $adb -s $Tv get-state 2>&1) -join '') -notmatch 'device') {
    throw "TV $Tv is not reachable over ADB. Turn on USB debugging and check the TV's IP address."
}
if ($DryRun) { "DRY RUN: nothing will be changed" }

# 1. every package that was enabled before and is disabled now
$wasEnabled = Get-Content "$backup\packages-enabled.txt" | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }
$nowDisabled = (& $adb -s $Tv shell pm list packages -d) | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }
$toEnable = @($nowDisabled | Where-Object { $wasEnabled -contains $_ })
"== Re-enabling $($toEnable.Count) package(s)"
foreach ($p in $toEnable) { Shell @('pm', 'enable', '--user', '0', $p) }

# 2. settings Aura's setup changed
"== Restoring settings"
foreach ($k in 'screensaver_components', 'screensaver_default_component') {
    $v = BackedUp 'settings-secure.txt' $k
    if ($v) { Shell @('settings', 'put', 'secure', $k, $v) }
}
foreach ($k in 'window_animation_scale', 'transition_animation_scale') {
    $v = BackedUp 'settings-global.txt' $k
    if ($v) { Shell @('settings', 'put', 'global', $k, $v) } else { Shell @('settings', 'delete', 'global', $k) }
}

# 2b. the speed tuning (background restrictions, Aura's usage access, any leftover freezer flag)
"== Undoing speed tuning"
if ($DryRun) { "> tools\tune.ps1 -Undo" } else { & (Join-Path $PSScriptRoot "tune.ps1") -Tv $Tv -Undo }

# 3. Aura's force-lock admin (an active admin would block uninstalling Aura)
"== Releasing Aura's standby admin"
Shell @('am', 'broadcast', '-n', 'app.aura.home/.Release')

# 4. Home back to what it was
$original = (Get-Content "$backup\home-activity.txt" | Where-Object { $_ -match '/' } | Select-Object -Last 1).Trim()
"== Home -> $original"
Shell @('cmd', 'package', 'set-home-activity', $original)

if ($RemoveAura) {
    "== Uninstalling Aura"
    Shell @('pm', 'uninstall', 'app.aura.home')
}
Shell @('input', 'keyevent', 'KEYCODE_HOME')

if (-not $DryRun) {
    "== Done. Home now resolves to: " + ((& $adb -s $Tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) | Select-Object -Last 1)
    "   Disabled packages left: $(@((& $adb -s $Tv shell pm list packages -d) | Where-Object { $_ }).Count) (there were $(@(Get-Content "$backup\packages-disabled.txt" | Where-Object { $_ }).Count) before)"
}
