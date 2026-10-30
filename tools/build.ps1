# Build Aura.apk with the Android SDK tools directly (aapt2 -> javac -> d8 -> zipalign -> apksigner).
# Targets API 28 on purpose: from 29 Android only lets system apps join, leave or toggle Wi-Fi,
# which Control Center's Wi-Fi sheet needs. It runs from Android 5.1 (API 22, minSdk) and is tested on Android 11.
# Compat.java and version checks keep newer calls off older TVs; features with no 5.1 counterpart switch off.
# -TestPackage builds a copy under another package name (app.aura.home.test), installed beside the
# real Aura: a first install to test from scratch without wiping anyone's settings
param([int]$VersionCode = 1, [string]$VersionName = "1.0", [switch]$TestPackage)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$proj = Join-Path $root "launcher"
$src = Join-Path $proj "src\main"
$out = Join-Path $proj "build"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } | Select-Object -Last 1 -ExpandProperty FullName
$jar = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName "android.jar" }
# any JDK 11+; Android Studio's bundled one is used when JAVA_HOME isn't set
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

function Step($name, [scriptblock]$cmd) {
    Write-Host "== $name"
    $ErrorActionPreference = "Continue"
    & $cmd 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$name failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

Step "aapt2 compile" { & "$bt\aapt2.exe" compile --dir "$src\res" -o "$out\res.zip" }
$rename = if ($TestPackage) { @("--rename-manifest-package", "app.aura.home.test") } else { @() }
$apkOut = if ($TestPackage) { Join-Path $proj "AuraTest.apk" } else { Join-Path $proj "Aura.apk" }
Step "aapt2 link" {
    & "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$src\AndroidManifest.xml" `
        --java "$out\gen" --min-sdk-version 22 --target-sdk-version 28 `
        --version-code $VersionCode --version-name $VersionName @rename "$out\res.zip"
}
$java = @(Get-ChildItem "$src\java", "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$boot = "$jar;$bt\core-lambda-stubs.jar"
Step "javac" { & javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath $boot -classpath $jar -d "$out\classes" @java }
# one jar instead of every .class path: d8.bat would overflow cmd.exe's 8 KB command line
Step "jar" { & jar --create --file "$out\classes.jar" -C "$out\classes" . }
Step "d8" { & "$bt\d8.bat" --release --min-api 22 --lib $jar --output "$out\dex" "$out\classes.jar" }

Copy-Item "$out\base.apk" "$out\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex") | Out-Null
# assets added by hand: aapt2 on Windows writes '\' separators, which Android can't resolve
$assets = Join-Path $src "assets"
Get-ChildItem $assets -Recurse -File | ForEach-Object {
    $name = "assets/" + $_.FullName.Substring($assets.Length + 1).Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name, [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
}
$zip.Dispose()

Step "zipalign" { & "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk" }
$ks = Join-Path $proj "aura.keystore"
if (-not (Test-Path $ks)) {
    Step "keytool" { & keytool -genkeypair -keystore $ks -alias aura -keyalg RSA -keysize 2048 -validity 10000 -storepass auraaura -keypass auraaura -dname "CN=Aura Home" }
}
Step "apksigner" { & "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:auraaura --key-pass pass:auraaura --out $apkOut "$out\aligned.apk" }
Write-Host ("Built {0} ({1:N0} KB)" -f $apkOut, ((Get-Item $apkOut).Length / 1KB))
