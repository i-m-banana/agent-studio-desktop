[CmdletBinding()]
param([switch]$SkipPreflight)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runDirectory = Join-Path $projectRoot '.run'
$composeFile = Join-Path $projectRoot 'docker/compose.yml'
if (-not $SkipPreflight) { & (Join-Path $PSScriptRoot 'preflight.ps1') -RequireOllama; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
if (-not (Test-Path -LiteralPath $runDirectory)) { New-Item -ItemType Directory -Path $runDirectory | Out-Null }
$webDirectory = Join-Path $projectRoot 'web'
$viteCommand = Join-Path $webDirectory 'node_modules/.bin/vite.cmd'
if (-not (Test-Path -LiteralPath $viteCommand)) {
    Write-Host 'Frontend dependencies are incomplete. Running npm install...'
    Push-Location $webDirectory
    try { & npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } }
    finally { Pop-Location }
}
foreach ($port in @(8080, 5173)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use. Stop the existing backend/web process before starting this release."
    }
}

& docker compose -f $composeFile up -d
if ($LASTEXITCODE -ne 0) { throw 'Database containers failed to start.' }

$backendOut = Join-Path $runDirectory 'backend.out.log'
$backendErr = Join-Path $runDirectory 'backend.err.log'
$webOut = Join-Path $runDirectory 'web.out.log'
$webErr = Join-Path $runDirectory 'web.err.log'
$commandProcessor = Join-Path $env:SystemRoot 'System32/cmd.exe'
$backend = Start-Process $commandProcessor -WindowStyle Hidden -WorkingDirectory (Join-Path $projectRoot 'backend') -PassThru -ArgumentList '/d','/s','/c','mvn spring-boot:run' -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr
$web = Start-Process $commandProcessor -WindowStyle Hidden -WorkingDirectory $webDirectory -PassThru -ArgumentList '/d','/s','/c','npm run dev -- --host 127.0.0.1' -RedirectStandardOutput $webOut -RedirectStandardError $webErr
Set-Content -LiteralPath (Join-Path $runDirectory 'backend.launcher.pid') -Value $backend.Id
Set-Content -LiteralPath (Join-Path $runDirectory 'web.launcher.pid') -Value $web.Id
Set-Content -LiteralPath (Join-Path $runDirectory 'backend.pid') -Value $backend.Id
Set-Content -LiteralPath (Join-Path $runDirectory 'web.pid') -Value $web.Id

$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $status = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/system/status' -TimeoutSec 2
        if ($status.status -eq 'UP' -and $status.version -eq (Get-Content (Join-Path $projectRoot 'VERSION') -Raw).Trim()) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
$webReady = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try { $response = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 2; if ($response.StatusCode -eq 200) { $webReady = $true; break } }
    catch { Start-Sleep -Seconds 1 }
}
if (-not $ready -or -not $webReady) {
    & (Join-Path $PSScriptRoot 'stop.ps1')
    if (-not $ready) { throw "Backend did not become ready in 60 seconds. See $backendErr" }
    throw "Web application did not become ready in 30 seconds. See $webErr"
}
$backendListener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -First 1
$webListener = Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $backendListener -or -not $webListener) {
    & (Join-Path $PSScriptRoot 'stop.ps1')
    throw 'Applications answered health checks but their listener process IDs could not be recorded.'
}
Set-Content -LiteralPath (Join-Path $runDirectory 'backend.pid') -Value $backendListener.OwningProcess
Set-Content -LiteralPath (Join-Path $runDirectory 'web.pid') -Value $webListener.OwningProcess
Write-Host 'Agent Studio Desktop started: http://localhost:5173/' -ForegroundColor Green
Write-Host 'Open System Diagnostics to verify model keys, embedding, and MCP.'
