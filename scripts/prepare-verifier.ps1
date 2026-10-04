[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRepository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskContext = Join-Path $taskRepository '.run/verifier-build'
New-Item -ItemType Directory -Path $taskContext -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRepository 'docker/verifier/Dockerfile') -Destination (Join-Path $taskContext 'Dockerfile')
Copy-Item -LiteralPath (Join-Path $taskRepository 'docker/verifier/.dockerignore') -Destination (Join-Path $taskContext '.dockerignore')
Copy-Item -LiteralPath (Join-Path $taskRepository 'backend/pom.xml') -Destination (Join-Path $taskContext 'pom.xml')
Copy-Item -LiteralPath (Join-Path $taskRepository 'docker/verifier/spring-boot-3.2.5-pom.xml') -Destination (Join-Path $taskContext 'spring-boot-3.2.5-pom.xml')
Copy-Item -LiteralPath (Join-Path $taskRepository 'docker/verifier/maven-central-settings.xml') -Destination (Join-Path $taskContext 'maven-central-settings.xml')
Copy-Item -LiteralPath (Join-Path $taskRepository 'web/package.json') -Destination (Join-Path $taskContext 'package.json')
Copy-Item -LiteralPath (Join-Path $taskRepository 'web/package-lock.json') -Destination (Join-Path $taskContext 'package-lock.json')
# Context contains toolchain metadata only; no credentials, database, source or uploads.
& docker build --tag agentstudio-verifier:local $taskContext
if ($LASTEXITCODE -ne 0) { throw 'Isolation toolchain build failed.' }
$taskImage = & docker image inspect --format '{{.Id}}' agentstudio-verifier:local
if ($LASTEXITCODE -ne 0 -or $taskImage -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Pinned image identity could not be read.' }
Write-Output "AGENT_STUDIO_SANDBOX_IMAGE=$taskImage"
Write-Output "AGENT_STUDIO_DOCKER_EXECUTABLE=$((Get-Command docker.exe -ErrorAction Stop).Source)"
