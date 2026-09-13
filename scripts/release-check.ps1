[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'preflight.ps1'); if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$version = (Get-Content (Join-Path $projectRoot 'VERSION') -Raw).Trim()
$pom = Get-Content (Join-Path $projectRoot 'backend/pom.xml') -Raw
$package = Get-Content (Join-Path $projectRoot 'web/package.json') -Raw | ConvertFrom-Json
$applicationConfig = Get-Content (Join-Path $projectRoot 'backend/src/main/resources/application.yml') -Raw
$buildVersion = Get-Content (Join-Path $projectRoot 'backend/src/main/java/com/agentstudio/system/BuildVersion.java') -Raw
if ($pom -notmatch "<version>$([regex]::Escape($version))</version>" -or $package.version -ne $version -or
    $applicationConfig -notmatch "version: $([regex]::Escape($version))" -or $buildVersion -notmatch "VALUE = `"$([regex]::Escape($version))`"") {
    throw 'VERSION, backend, runtime configuration, and frontend versions do not match.'
}

Push-Location (Join-Path $projectRoot 'backend')
try { & mvn test; if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed.' } } finally { Pop-Location }
$temporaryRoot = [IO.Path]::GetTempPath()
$temporaryWeb = Join-Path $temporaryRoot ("agent-studio-release-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryWeb | Out-Null
try {
    foreach ($file in @('package.json', 'package-lock.json', 'index.html', 'tsconfig.json', 'tsconfig.app.json', 'tsconfig.node.json', 'vite.config.ts')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot "web/$file") -Destination $temporaryWeb
    }
    Copy-Item -LiteralPath (Join-Path $projectRoot 'web/src') -Destination $temporaryWeb -Recurse
    Push-Location $temporaryWeb
    try { & npm ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency verification failed.' }; & npm run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' } } finally { Pop-Location }
} finally {
    $resolvedTemporary = [IO.Path]::GetFullPath($temporaryWeb)
    $expectedPrefix = [IO.Path]::GetFullPath((Join-Path $temporaryRoot 'agent-studio-release-'))
    if (-not $resolvedTemporary.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to clean an unexpected temporary path.' }
    if (Test-Path -LiteralPath $resolvedTemporary) { Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force }
}

Push-Location $projectRoot
try {
    $changes = & git -c "safe.directory=$projectRoot" status --short
    if ($LASTEXITCODE -ne 0) { Write-Host 'Notice: Git worktree status could not be read.' -ForegroundColor Yellow }
    elseif ($changes) { Write-Host 'Notice: the worktree has uncommitted changes. Review and commit before tagging.' -ForegroundColor Yellow }
} finally { Pop-Location }
Write-Host "Release candidate $version verification passed." -ForegroundColor Green
