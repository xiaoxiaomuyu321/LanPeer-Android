param([switch]$SkipLint)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $localJava = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools\jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJava) { $env:JAVA_HOME = $localJava.FullName }
    $localSdk = Join-Path $PSScriptRoot '.tools\android-sdk'
    if (Test-Path -LiteralPath $localSdk) { $env:ANDROID_HOME = $localSdk }
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools\gradle-home'
    $gradle = Join-Path $PSScriptRoot '.tools\gradle-8.9\bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
    $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest')
    if (-not $SkipLint) { $tasks += ':app:lintDebug' }
    & $gradle --no-daemon --console=plain @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    Write-Output 'APK: app\build\outputs\apk\debug\app-debug.apk'
} finally { Pop-Location }
