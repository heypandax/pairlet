# cc-pocket daemon — one-command Windows install (x86_64):
#
#   irm https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.ps1 | iex
#
# The Claude Code distribution model: downloads the latest self-contained release (bundled JRE — no
# system Java), verifies it against the release's SHA256SUMS, installs it under
#   %LOCALAPPDATA%\cc-pocket\versions\<ver>\cc-pocket-daemon\
# registers the logon background service on that version (starts it right away), and drops straight
# into pairing. Re-run the same line to upgrade — or just run `cc-pocket-daemon update`: the daemon
# checks daily and can update itself. Scoop users can keep `scoop install cc-pocket-daemon` instead.
$ErrorActionPreference = "Stop"

$repo = "heypandax/cc-pocket"
$root = Join-Path $env:LOCALAPPDATA "cc-pocket"

Write-Host "-- cc-pocket daemon installer --"
$rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest"
$ver = $rel.tag_name -replace '^v', ''
$asset = $rel.assets | Where-Object { $_.name -like "*windows-x86_64.zip" } | Select-Object -First 1
if (-not $asset) { throw "no Windows asset on the latest release ($($rel.tag_name)) — see https://github.com/$repo/releases" }

$zip = Join-Path $env:TEMP $asset.name
Write-Host "downloading $($asset.name) ($($rel.tag_name))..."
Invoke-WebRequest $asset.browser_download_url -OutFile $zip

# verify against the release's SHA256SUMS (older releases may not have one — warn and continue)
$sums = $rel.assets | Where-Object { $_.name -eq "SHA256SUMS" } | Select-Object -First 1
if ($sums) {
    $line = (Invoke-RestMethod $sums.browser_download_url) -split "`n" | Where-Object { $_ -match [regex]::Escape($asset.name) } | Select-Object -First 1
    if ($line -and $line -match '^([0-9a-fA-F]{64})') {
        $expected = $Matches[1].ToLower()
        $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
        if ($actual -ne $expected) { throw "checksum mismatch for $($asset.name) (expected $expected, got $actual) - corrupted download or tampered artifact" }
        Write-Host "checksum OK"
    } else { Write-Host "warning: SHA256SUMS has no entry for $($asset.name) - skipping verification" }
} else { Write-Host "warning: release has no SHA256SUMS - skipping verification" }

# stop a running daemon so binaries can be replaced (the service restarts it below)
schtasks /End /TN cc-pocket-daemon 2>$null | Out-Null
Get-Process cc-pocket-daemon -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# versioned layout (the daemon's self-update uses the same one)
$dest = Join-Path $root "versions\$ver"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Expand-Archive $zip $dest -Force
Remove-Item $zip

$exe = Join-Path $dest "cc-pocket-daemon\cc-pocket-daemon.exe"
if (-not (Test-Path $exe)) {
    $found = Get-ChildItem $dest -Recurse -Filter "cc-pocket-daemon.exe" | Select-Object -First 1
    if (-not $found) { throw "cc-pocket-daemon.exe not found in the archive" }
    $exe = $found.FullName
}

Write-Host "registering + starting the background service..."
& $exe service-install --apply --exec $exe

# migrate the legacy flat layout (early 1.2.0 installs) + prune old versions (keep newest 2)
$legacy = Join-Path $root "daemon"
if (Test-Path $legacy) { Remove-Item $legacy -Recurse -Force -ErrorAction SilentlyContinue }
Get-ChildItem (Join-Path $root "versions") -Directory |
    Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } -Descending |
    Select-Object -Skip 2 |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host ""
Write-Host "installed: $exe"
Write-Host "upgrade later with:  `"$exe`" update   (the daemon also checks daily)"
Write-Host ""
Write-Host "opening pairing now - scan the QR with the CC Pocket app:"
& $exe pair
