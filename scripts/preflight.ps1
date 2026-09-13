[CmdletBinding()]
param([switch]$RequireOllama)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $projectRoot 'docker/compose.yml'
$failures = [System.Collections.Generic.List[string]]::new()

function Test-Command([string]$Name, [string]$Hint) {
    if (Get-Command $Name -ErrorAction SilentlyContinue) {
        Write-Host "[READY] $Name"
    } else {
        Write-Host "[FAILED] $Name - $Hint" -ForegroundColor Red
        $failures.Add($Name)
    }
}

Write-Host "Agent Studio Desktop preflight - $(Get-Content (Join-Path $projectRoot 'VERSION'))"
Test-Command 'java' 'Java 21 is required'
Test-Command 'mvn' 'Maven 3.9+ is required'
Test-Command 'node' 'Node.js 20+ is required'
Test-Command 'npm' 'npm is required'
Test-Command 'docker' 'Docker Desktop is required'
if ($RequireOllama) { Test-Command 'ollama' 'The default embedding provider requires Ollama' }

if (Get-Command mvn -ErrorAction SilentlyContinue) {
    $mavenRuntime = (& mvn -version) -join "`n"
    $javaLine = ($mavenRuntime -split "`n" | Where-Object { $_ -match '^Java version:' } | Select-Object -First 1)
    Write-Host "        Maven runtime: $javaLine"
    if ($javaLine -notmatch '^Java version: 21(?:\.|,|$)') { $failures.Add('Maven must run with Java 21') }
}
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { $failures.Add('Docker Desktop is not running') }
    & docker compose -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { $failures.Add('docker/compose.yml is invalid') }
}

$dataDirectory = Join-Path $projectRoot 'data'
if (-not (Test-Path -LiteralPath $dataDirectory)) { New-Item -ItemType Directory -Path $dataDirectory | Out-Null }
try {
    $probe = Join-Path $dataDirectory '.write-probe'
    Set-Content -LiteralPath $probe -Value 'ok' -NoNewline
    Remove-Item -LiteralPath $probe
    Write-Host '[READY] data directory writable'
} catch { $failures.Add("data directory is not writable: $($_.Exception.Message)") }

if ($failures.Count -gt 0) {
    Write-Host "Preflight failed: $($failures -join '; ')" -ForegroundColor Red
    exit 1
}
Write-Host 'Preflight passed.' -ForegroundColor Green
