# Aura's tests, no TV needed: builds Aura, then starts the real app on simulated Android TVs
# (Robolectric, Android 7.1 and 11) and walks it: start-up and the boot screen, the home screen with
# the remote, Control Center, Settings' pages, the welcome on a fresh install, the screensaver making
# room, the phone's report page, and the Smart Stack's learning. Needs a JDK 17+ and Maven.
param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $NoBuild) { & (Join-Path $PSScriptRoot "build.ps1") }
# the same SDK and JDK build.ps1 uses
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$jar = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName "android.jar" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$ErrorActionPreference = "Continue"
& mvn -f (Join-Path $root "tests\pom.xml") -q test "-Dandroid.jar=$jar"
if ($LASTEXITCODE -ne 0) { throw "Some tests failed: see tests\target\surefire-reports" }
Write-Host "All tests passed. What each run noted (start times, problem reports) is in tests\target\*.txt"
