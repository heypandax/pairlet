# cc-pocket daemon — one-command Windows install (x86_64):
#
#   irm https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.ps1 | iex
#
# Downloads the latest self-contained release (bundled JRE — no system Java needed), installs it
# under %LOCALAPPDATA%\cc-pocket, registers the logon background service (starts it right away),
# and drops straight into pairing. Re-run the same line to upgrade. Scoop users can keep using
# `scoop install cc-pocket-daemon` instead — both paths register the same service.
$ErrorActionPreference = "Stop"

$repo = "heypandax/cc-pocket"
$dest = Join-Path $env:LOCALAPPDATA "cc-pocket\daemon"

Write-Host "-- cc-pocket daemon installer --"
$rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest"
$asset = $rel.assets | Where-Object { $_.name -like "*windows-x86_64.zip" } | Select-Object -First 1
if (-not $asset) { throw "no Windows asset on the latest release ($($rel.tag_name)) — see https://github.com/$repo/releases" }

$zip = Join-Path $env:TEMP $asset.name
Write-Host "downloading $($asset.name) ($($rel.tag_name))..."
Invoke-WebRequest $asset.browser_download_url -OutFile $zip

# stop a running daemon so the binary can be replaced (the service restarts it below)
schtasks /End /TN cc-pocket-daemon 2>$null | Out-Null
Get-Process cc-pocket-daemon -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Expand-Archive $zip $dest -Force
Remove-Item $zip

$exe = Get-ChildItem $dest -Recurse -Filter "cc-pocket-daemon.exe" | Select-Object -First 1
if (-not $exe) { throw "cc-pocket-daemon.exe not found in the archive" }

Write-Host "registering + starting the background service..."
& $exe.FullName service-install --apply --exec $exe.FullName

Write-Host ""
Write-Host "installed: $($exe.FullName)"
Write-Host "pair again later with:  `"$($exe.FullName)`" pair"
Write-Host ""
Write-Host "opening pairing now - scan the QR with the CC Pocket app:"
& $exe.FullName pair
