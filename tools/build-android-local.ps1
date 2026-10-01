param(
    [string]$SdkPath = 'C:\Android\Sdk',
    [string]$BuildToolsVersion = '35.0.0',
    [string]$GradleCache = $env:GRADLE_USER_HOME,
    [string]$DebugKeystore = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$OutputDirectory
)
# SDK-only fallback for the documented host Gradle loopback failure. No downloads or installs.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$gradleText = Get-Content (Join-Path $root 'app\build.gradle') -Raw
$version = [regex]::Match($gradleText, "versionName '([^']+)'").Groups[1].Value
$versionCode = [regex]::Match($gradleText, 'versionCode (\d+)').Groups[1].Value
$build = Join-Path $root ("build\manual-android-v$version-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$out = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $root "outputs\MsgDock-v$version" }
$bt = Join-Path $SdkPath "build-tools\$BuildToolsVersion"
$platform = Join-Path $SdkPath 'platforms\android-36\android.jar'
$javaBin = Split-Path (Get-Command javac.exe -ErrorAction Stop).Source
if (-not $GradleCache) { $GradleCache = Join-Path $env:USERPROFILE '.gradle' }
function Dependency([string]$group, [string]$artifact, [string]$ver) {
    $dir = Join-Path $GradleCache "caches\modules-2\files-2.1\$group\$artifact\$ver"
    $file = Get-ChildItem $dir -Recurse -File -Filter "$artifact-$ver.jar" | Select-Object -First 1
    if (-not $file) { throw "Missing cached test dependency: $group/$artifact/$ver; run Gradle on a working host first." }
    return $file.FullName
}
$junit = Dependency 'junit' 'junit' '4.13.2'
$hamcrest = Dependency 'org.hamcrest' 'hamcrest-core' '1.3'
$json = Dependency 'org.json' 'json' '20240303'
foreach ($required in @($platform, $DebugKeystore, "$bt\aapt2.exe", "$bt\lib\d8.jar", "$bt\lib\apksigner.jar")) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing tool/input: $required" }
}
function Run([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $exe (exit $LASTEXITCODE)" }
}
New-Item -ItemType Directory -Force -Path $build,$out,"$build\generated","$build\classes","$build\dex","$build\test-classes" | Out-Null
[xml]$manifest = Get-Content (Join-Path $root 'app\src\main\AndroidManifest.xml') -Raw
$androidNs = 'http://schemas.android.com/apk/res/android'
$manifest.manifest.SetAttribute('package', 'com.xgy.lansms')
$manifest.manifest.SetAttribute('versionCode', $androidNs, $versionCode) | Out-Null
$manifest.manifest.SetAttribute('versionName', $androidNs, $version) | Out-Null
$manifest.manifest.application.SetAttribute('debuggable', $androidNs, 'true') | Out-Null
$sdk = $manifest.CreateElement('uses-sdk')
$sdk.SetAttribute('minSdkVersion', $androidNs, '26') | Out-Null
$sdk.SetAttribute('targetSdkVersion', $androidNs, '36') | Out-Null
# Android derives application compatibility defaults while parsing <application>.
# uses-sdk must precede it, otherwise SDK-only APKs can become letterboxed.
$manifest.manifest.InsertBefore($sdk, $manifest.manifest.application) | Out-Null
$manifest.Save("$build\AndroidManifest.xml")
Run "$bt\aapt2.exe" @('compile','--dir',"$root\app\src\main\res",'-o',"$build\res.zip")
Run "$bt\aapt2.exe" @('link','-o',"$build\base.apk",'-I',$platform,'--manifest',"$build\AndroidManifest.xml",'--java',"$build\generated",'--auto-add-overlay',"$build\res.zip")
$sources = Get-ChildItem "$root\app\src\main\java","$build\generated" -Recurse -File -Filter '*.java'
$sourceArgs = $sources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
[IO.File]::WriteAllLines("$build\sources.txt", [string[]]$sourceArgs, [Text.UTF8Encoding]::new($false))
Run "$javaBin\javac.exe" @('-encoding','UTF-8','--release','17','-classpath',$platform,'-d',"$build\classes","@$build\sources.txt")
Run "$javaBin\jar.exe" @('cf',"$build\classes.jar",'-C',"$build\classes",'.')
Run "$javaBin\java.exe" @('-cp',"$bt\lib\d8.jar",'com.android.tools.r8.D8','--lib',$platform,'--min-api','26','--output',"$build\dex","$build\classes.jar")
Copy-Item -LiteralPath "$build\base.apk" -Destination "$build\with-dex.apk"
Run "$javaBin\jar.exe" @('uf',"$build\with-dex.apk",'-C',"$build\dex",'classes.dex')
Run "$bt\zipalign.exe" @('-f','4',"$build\with-dex.apk","$build\aligned.apk")
$apk = Join-Path $out "MsgDock-Android-v$version-debug.apk"
Run "$javaBin\java.exe" @('-jar',"$bt\lib\apksigner.jar",'sign','--ks',$DebugKeystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,"$build\aligned.apk")
$testSources = Get-ChildItem "$root\app\src\test\java" -Recurse -File -Filter '*.java'
$testArgs = $testSources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
[IO.File]::WriteAllLines("$build\test-sources.txt", [string[]]$testArgs, [Text.UTF8Encoding]::new($false))
$cp = "$json;$platform;$junit;$hamcrest;$build\classes;$build\test-classes"
Run "$javaBin\javac.exe" @('-encoding','UTF-8','--release','17','-classpath',$cp,'-d',"$build\test-classes","@$build\test-sources.txt")
$tests = $testSources | ForEach-Object { 'com.xgy.lansms.' + $_.BaseName }
Run "$javaBin\java.exe" (@('-cp',$cp,'org.junit.runner.JUnitCore') + $tests)
Run "$javaBin\java.exe" @('-jar',"$bt\lib\apksigner.jar",'verify','--verbose','--print-certs',$apk)
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
Write-Output "APK: $apk"
Write-Output 'SDK compilation and JVM tests are not a substitute for Gradle lint or physical-device acceptance.'
