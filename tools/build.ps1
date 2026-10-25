# Windows twin of tools/build.sh. Needs Git, a JDK 17+ (Android Studio's jbr works) and the Android SDK.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$root = (Get-Location).Path
$pin = '86a69372f9886ab6392ad2c3cf95f9a0e1c54644'
$work = 'build\cleanyourtv'
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:JAVA_HOME) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr } else { throw 'Set JAVA_HOME to a JDK 17+' }
}
if (-not (Test-Path "$work\.git")) { git clone --filter=blob:none https://github.com/Gathin23/cleanyourtv.git $work }
git -C $work fetch -q origin $pin
git -C $work checkout -q -f $pin
git -C $work apply "$root\patches\cleanyourtv-launcher.patch"
"sdk.dir=$($env:ANDROID_HOME -replace '\\', '/')" | Out-File -Encoding ascii "$work\launcher\local.properties"
Push-Location "$work\launcher"
foreach ($i in 1..3) { .\gradlew.bat assembleRelease -q --console=plain; if ($LASTEXITCODE -eq 0) { break }; if ($i -eq 3) { throw 'launcher build failed' }; Start-Sleep 30 }
Pop-Location
Copy-Item "$work\launcher\app\build\outputs\apk\release\app-release.apk" 'app\src\main\assets\tvhome.apk' -Force
"sdk.dir=$($env:ANDROID_HOME -replace '\\', '/')" | Out-File -Encoding ascii local.properties
foreach ($i in 1..3) { .\gradlew.bat assembleRelease -q --console=plain; if ($LASTEXITCODE -eq 0) { break }; if ($i -eq 3) { throw 'app build failed' }; Start-Sleep 30 }
Write-Host 'Built: app\build\outputs\apk\full\release\app-full-release.apk'
Write-Host '       app\build\outputs\apk\lite\release\app-lite-release.apk'
