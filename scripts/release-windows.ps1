# Build -> jpackage (bundled JRE) -> zip for Windows (x86_64).
# Produces a self-contained cc-pocket-daemon app-image (runs with no system Java).
#
# Unlike macOS there is no notarization here. Authenticode signing is optional and skipped by
# default; set $env:WINDOWS_CERT_* to enable it later if SmartScreen warnings become a problem.
#
# Prereqs:
#   - JDK 17 with jpackage on PATH (JAVA_HOME set) -- jpackage bundles the host JRE, no cross-build,
#     so this must run on a Windows x86_64 runner (see .github/workflows/release.yml).
#
# Usage: pwsh scripts/release-windows.ps1 [version]
$ErrorActionPreference = 'Stop'

$Version = if ($args.Count -ge 1) { $args[0] } else { '1.0.0' }
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $Root

# gradle.properties pins org.gradle.java.home to the Mac dev box's JDK, which doesn't exist on a
# Windows runner. Override it from JAVA_HOME (setup-java sets this). jpackage bundles whichever JDK
# runs gradle, so on a Windows x86_64 runner this yields an x86_64 image.
$gradleArgs = @(':daemon:packageDaemon', '-q', "-PappVersion=$Version")
if ($env:JAVA_HOME) { $gradleArgs += "-Dorg.gradle.java.home=$env:JAVA_HOME" }

Write-Host '==> gradle build + jpackage (bundled JRE)'
& (Join-Path $Root 'gradlew.bat') @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "gradle build failed ($LASTEXITCODE)" }

$App = Join-Path $Root 'daemon\build\jpackage\cc-pocket-daemon'
if (-not (Test-Path $App)) { throw "jpackage output not found at $App" }

Write-Host '==> zip + checksum'
$Out = "cc-pocket-daemon-$Version-windows-x86_64.zip"
if (Test-Path $Out) { Remove-Item $Out }
# Compress the app-image dir itself (keepParent) so it extracts to cc-pocket-daemon\.
Compress-Archive -Path $App -DestinationPath $Out
$Sha = (Get-FileHash -Algorithm SHA256 $Out).Hash.ToLower()

Write-Host ''
Write-Host "    artifact : $Out"
Write-Host "    sha256   : $Sha"
Write-Host ''
Write-Host 'Install on the target machine:'
Write-Host "  Expand-Archive $Out -DestinationPath `$env:LOCALAPPDATA\Programs\"
Write-Host '  $env:LOCALAPPDATA\Programs\cc-pocket-daemon\cc-pocket-daemon.exe pair'
