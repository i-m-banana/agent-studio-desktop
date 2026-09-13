[CmdletBinding()]
param([switch]$StopDatabases)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runDirectory = Join-Path $projectRoot '.run'
$composeFile = Join-Path $projectRoot 'docker/compose.yml'
function Stop-RecordedProcessTree([int]$RootProcessId) {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) { Stop-RecordedProcessTree -RootProcessId $child.ProcessId }
    if (Get-Process -Id $RootProcessId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
    }
}
foreach ($name in @('backend.launcher', 'web.launcher', 'backend', 'web')) {
    $pidFile = Join-Path $runDirectory "$name.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { continue }
    $processId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) { Stop-RecordedProcessTree -RootProcessId $processId; Write-Host "Stopped $name (PID $processId)" }
    Remove-Item -LiteralPath $pidFile
}
if ($StopDatabases) { & docker compose -f $composeFile stop }
