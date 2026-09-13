[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backupRoot = Join-Path $projectRoot 'backups'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$destination = Join-Path $backupRoot $stamp
$composeFile = Join-Path $projectRoot 'docker/compose.yml'
New-Item -ItemType Directory -Path $destination -Force | Out-Null

$mysqlUser = (& docker compose -f $composeFile exec -T mysql printenv MYSQL_USER).Trim()
$mysqlPassword = (& docker compose -f $composeFile exec -T mysql printenv MYSQL_PASSWORD).Trim()
$mysqlDatabase = (& docker compose -f $composeFile exec -T mysql printenv MYSQL_DATABASE).Trim()
$mysqlDumpContent = & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$mysqlPassword" mysql mysqldump --single-transaction --no-tablespaces -u $mysqlUser $mysqlDatabase
$mysqlExitCode = $LASTEXITCODE
if ($mysqlExitCode -ne 0) { throw 'MySQL backup failed.' }
$mysqlDumpContent | Set-Content -LiteralPath (Join-Path $destination 'mysql.sql') -Encoding utf8
$postgresUser = (& docker compose -f $composeFile exec -T pgvector printenv POSTGRES_USER).Trim()
$postgresDatabase = (& docker compose -f $composeFile exec -T pgvector printenv POSTGRES_DB).Trim()
$vectorDumpContent = & docker compose -f $composeFile exec -T pgvector pg_dump -U $postgresUser -d $postgresDatabase --clean --if-exists
$vectorExitCode = $LASTEXITCODE
if ($vectorExitCode -ne 0) { throw 'pgvector backup failed.' }
$vectorDumpContent | Set-Content -LiteralPath (Join-Path $destination 'pgvector.sql') -Encoding utf8

$dataDirectory = Join-Path $projectRoot 'data'
if (Test-Path -LiteralPath $dataDirectory) { Compress-Archive -LiteralPath $dataDirectory -DestinationPath (Join-Path $destination 'data.zip') }
$manifest = [ordered]@{ version = (Get-Content (Join-Path $projectRoot 'VERSION') -Raw).Trim(); createdAt = (Get-Date).ToString('o'); files = @('mysql.sql', 'pgvector.sql', 'data.zip') }
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
Write-Host "Backup completed: $destination" -ForegroundColor Green
