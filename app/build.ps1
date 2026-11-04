# ============================================================================
#  YouTube VPN Gate - build the APK without Gradle (aapt2 + javac + d8 + apksigner)
#
#  Requirements:
#    - JDK 17 or newer, JAVA_HOME pointing at it
#    - Android SDK with platform 34 and build-tools 34.0.0
#      (ANDROID_HOME or ANDROID_SDK_ROOT)
#    - debug keystore at %USERPROFILE%\.android\debug.keystore, or DEBUG_KEYSTORE;
#      it is generated with keytool when missing
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File build.ps1
#    powershell -ExecutionPolicy Bypass -File build.ps1 -Out MyGate.apk
# ============================================================================
param(
    [string]$Out = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'YouTube-VPN-Gate.apk' }

$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    throw 'JAVA_HOME is not set or has no bin\javac.exe (JDK 17+ required)'
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Join-Path $sdk 'build-tools\34.0.0'
$android = Join-Path $sdk 'platforms\android-34\android.jar'
if (-not (Test-Path $android)) { throw "Android platform 34 not found: $android" }
if (-not (Test-Path (Join-Path $bt 'aapt2.exe'))) { throw "build-tools 34.0.0 not found: $bt" }

$key = $env:DEBUG_KEYSTORE
if (-not $key) { $key = Join-Path $env:USERPROFILE '.android\debug.keystore' }
if (-not (Test-Path $key)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $key) | Out-Null
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v -keystore $key `
        -storepass android -alias androiddebugkey -keypass android `
        -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed to create a debug keystore' }
}

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$build = Join-Path $root 'build'
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build, "$build\gen", "$build\classes", "$build\dex" | Out-Null

Write-Host '1/7 aapt2 compile + link'
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

& "$bt\aapt2.exe" link -o "$build\base.apk" -I $android `
    --manifest "$root\AndroidManifest.xml" -R "$build\res.zip" --auto-add-overlay `
    --java "$build\gen" --min-sdk-version 26 --target-sdk-version 34 `
    --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '2/7 javac'
$javaFiles = @(Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object FullName)
$javaFiles += @(Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdk 'bin\javac.exe') --release 8 -encoding UTF-8 -parameters `
    -classpath $android -d "$build\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '3/7 d8'
$classFiles = @(Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --lib $android --min-api 26 --output "$build\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '4/7 classes.dex -> base.apk'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\base.apk", 'Update')
try {
    $old = $zip.GetEntry('classes.dex')
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$build\dex\classes.dex", 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

Write-Host '5/7 zipalign'
& "$bt\zipalign.exe" -f 4 "$build\base.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '6/7 apksigner'
& "$bt\apksigner.bat" sign --ks $key --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $Out "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Host '7/7 verify'
& "$bt\apksigner.bat" verify --print-certs $Out
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$size = (Get-Item $Out).Length
Write-Host ''
Write-Host ("Done: {0} ({1:N1} KB)" -f $Out, ($size / 1KB))
