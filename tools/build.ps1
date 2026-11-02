param([string[]]$Tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleRelease'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolchainRoot = Join-Path $env:USERPROFILE 'AndroidToolchain'
if (-not $env:JAVA_HOME) {
    $jdkDirectory = Get-ChildItem -LiteralPath $toolchainRoot -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'jdk-*' } | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdkDirectory) { $env:JAVA_HOME = $jdkDirectory.FullName }
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Set JAVA_HOME to a JDK 17 or newer installation.' }
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $toolchainRoot 'sdk'), (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
$sdkRoot = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $sdkRoot) { throw 'Install Android SDK platform 37.0 and build-tools 36.0.0, and set ANDROID_HOME.' }
$env:ANDROID_HOME = $sdkRoot
$localProperties = Join-Path $projectRoot 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties)) {
    [IO.File]::WriteAllText($localProperties, 'sdk.dir=' + $sdkRoot.Replace('\', '\\').Replace(':', '\:') + [Environment]::NewLine)
}
# Java/Gradle test workers on some Windows code pages cannot load classes from Unicode paths.
# The junction addresses the same project files; it does not copy or move the project.
$buildRoot = $projectRoot
if ($projectRoot -match '[^\x00-\x7F]') {
    $pathHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0, 12)
    $buildRoot = Join-Path $env:TEMP "NotiVault-$pathHash"
    if (Test-Path -LiteralPath $buildRoot) {
        $link = Get-Item -LiteralPath $buildRoot
        if ($link.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath($link.Target) -ne $projectRoot) { throw 'The build alias already exists and points to a different location.' }
    } else { New-Item -ItemType Junction -Path $buildRoot -Target $projectRoot | Out-Null }
}
Push-Location -LiteralPath $buildRoot
try { & (Join-Path $buildRoot 'gradlew.bat') @Tasks --console plain; if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" } }
finally { Pop-Location }
