param([switch]$Release, [switch]$DeviceTest)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$drive = @('R:', 'S:', 'T:') | Where-Object { -not (Test-Path "$_\") } | Select-Object -First 1
if (-not $drive) { throw 'No temporary drive letter is available.' }
& subst $drive $repoPath
try {
    $env:GRADLE_USER_HOME = "$drive\android\.gradle-home"
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    Push-Location "$drive\android"
    try {
        $gradle = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\wrapper\dists') -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match 'gradle-8\.14\.3' } | Select-Object -First 1 -ExpandProperty FullName
        [string[]]$tasks = if ($Release) { @(':app:assembleRelease') } elseif ($DeviceTest) { @(':app:connectedDebugAndroidTest') } else { @(':app:testDebugUnitTest', ':app:assembleDebug') }
        if ($gradle) { & $gradle @tasks --no-daemon --console plain }
        else { & .\gradlew.bat @tasks --no-daemon --console plain }
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    & subst $drive /D
    if ($DeviceTest -and (Get-Command adb -ErrorAction SilentlyContinue)) {
        & adb uninstall com.coco.dense.qa.test | Out-Null
        & adb uninstall com.coco.dense.qa | Out-Null
    }
}
