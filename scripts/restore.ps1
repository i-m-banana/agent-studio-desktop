[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BackupDirectory,
    [switch]$RestoreFiles,
    [switch]$ConfirmRestore
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmRestore) { throw 'Restore overwrites current databases. Add -ConfirmRestore to proceed.' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backupRoot = (Resolve-Path (Join-Path $projectRoot 'backups')).Path
$source = (Resolve-Path -LiteralPath $BackupDirectory).Path
$composeFile = Join-Path $projectRoot 'docker/compose.yml'
if (-not $source.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Restore source must be inside this project backups directory.' }
foreach ($file in @('manifest.json', 'mysql.sql', 'pgvector.sql')) { if (-not (Test-Path -LiteralPath (Join-Path $source $file))) { throw "Backup is missing $file" } }

$mysqlDump = Join-Path $source 'mysql.sql'
$vectorDump = Join-Path $source 'pgvector.sql'
$mysqlContainer = (& docker compose -f $composeFile ps -q mysql).Trim()
$vectorContainer = (& docker compose -f $composeFile ps -q pgvector).Trim()
if (-not $mysqlContainer -or -not $vectorContainer) { throw 'Database containers must be running before restore.' }
& docker cp $mysqlDump "${mysqlContainer}:/tmp/agent-studio-restore.sql"
if ($LASTEXITCODE -ne 0) { throw 'Could not stage the MySQL dump.' }
& docker cp $vectorDump "${vectorContainer}:/tmp/agent-studio-vector-restore.sql"
if ($LASTEXITCODE -ne 0) { throw 'Could not stage the pgvector dump.' }
$mysqlRestoreCommand = 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < /tmp/agent-studio-restore.sql'
$vectorRestoreCommand = 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/agent-studio-vector-restore.sql'
& docker compose -f $composeFile exec -T mysql sh -c $mysqlRestoreCommand
if ($LASTEXITCODE -ne 0) { throw 'MySQL restore failed.' }
& docker compose -f $composeFile exec -T pgvector sh -c $vectorRestoreCommand
if ($LASTEXITCODE -ne 0) { throw 'pgvector restore failed.' }
if ($RestoreFiles) {
    $archive = Join-Path $source 'data.zip'
    if (-not (Test-Path -LiteralPath $archive)) { throw 'Backup does not contain data.zip.' }
    Expand-Archive -LiteralPath $archive -DestinationPath $projectRoot -Force
}
Write-Host 'Restore completed. Restart the backend and rerun System Diagnostics.' -ForegroundColor Green
