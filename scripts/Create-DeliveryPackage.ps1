[CmdletBinding()]
param(
  [string]$OutputDirectory = '',
  [string]$PgDumpPath = 'C:\Program Files\PostgreSQL\17\bin\pg_dump.exe',
  [switch]$IncludeNodeModules,
  [switch]$SkipDatabase,
  [switch]$SkipMinio
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $projectRoot "delivery\tiku-delivery-$timestamp" }
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path $output) { throw "Output already exists: $output" }

function Get-ProjectEnvValue([string]$Name) {
  $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
  if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
  $envFile = Join-Path $projectRoot '.env'
  if (-not (Test-Path $envFile)) { return $null }
  $entry = Get-Content $envFile | Where-Object { $_ -match ("^" + [regex]::Escape($Name) + "=(.*)$") } | Select-Object -First 1
  if ($null -eq $entry) { return $null }
  if ($entry -match ("^" + [regex]::Escape($Name) + "=(.*)$")) { return $matches[1].Trim('"') }
  return $null
}

function Copy-RequiredPath([string]$Source, [string]$Destination) {
  if (-not (Test-Path $Source)) { throw "Required path missing: $Source" }
  New-Item -ItemType Directory -Force (Split-Path -Parent $Destination) | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Write-Hashes([string]$Root) {
  $lines = Get-ChildItem -LiteralPath $Root -File -Recurse | Where-Object {
    $_.Name -notin @('checksums.sha256', 'manifest.json')
  } | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($Root.Length).TrimStart('\')
    "$( (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() )  $relative"
  }
  Set-Content -LiteralPath (Join-Path $Root 'checksums.sha256') -Value $lines -Encoding utf8
}

$jar = Join-Path $projectRoot 'backend\target\tiku-api-0.1.0.jar'
$dist = Join-Path $projectRoot 'frontend\dist'
if (-not (Test-Path $jar)) { throw 'Backend Jar is missing. Run mvn package first.' }
if (-not (Test-Path $dist)) { throw 'Frontend dist is missing. Run the frontend build first.' }
if (-not $SkipDatabase -and -not (Test-Path $PgDumpPath)) { throw "pg_dump not found: $PgDumpPath" }

New-Item -ItemType Directory -Force $output | Out-Null
$app = Join-Path $output 'application'
$data = Join-Path $output 'data'
$secrets = Join-Path $output 'secrets'
New-Item -ItemType Directory -Force $app, $data, $secrets | Out-Null

# Never copy .env. The template documents required variables without exposing values.
Copy-RequiredPath (Join-Path $projectRoot '.env.example') (Join-Path $app '.env.example')
foreach ($name in @('README.md', 'docs', 'scripts', 'infra')) {
  $source = Join-Path $projectRoot $name
  Copy-RequiredPath $source (Join-Path $app $name)
}
Copy-RequiredPath $jar (Join-Path $app 'backend\target\tiku-api-0.1.0.jar')
Copy-RequiredPath (Join-Path $projectRoot 'backend\pom.xml') (Join-Path $app 'backend\pom.xml')
Copy-RequiredPath (Join-Path $projectRoot 'backend\src') (Join-Path $app 'backend\src')
Copy-RequiredPath $dist (Join-Path $app 'frontend\dist')
foreach ($name in @('package.json', 'pnpm-lock.yaml', 'vite.config.ts', 'index.html', 'tsconfig.json')) {
  Copy-RequiredPath (Join-Path $projectRoot "frontend\$name") (Join-Path $app "frontend\$name")
}
Copy-RequiredPath (Join-Path $projectRoot 'frontend\src') (Join-Path $app 'frontend\src')
if (Test-Path (Join-Path $projectRoot 'samples')) { Copy-RequiredPath (Join-Path $projectRoot 'samples') (Join-Path $app 'samples') }
if ($IncludeNodeModules -and (Test-Path (Join-Path $projectRoot 'frontend\node_modules'))) {
  Copy-RequiredPath (Join-Path $projectRoot 'frontend\node_modules') (Join-Path $app 'frontend\node_modules')
}

if (-not $SkipDatabase) {
  $dbPassword = Get-ProjectEnvValue 'POSTGRES_PASSWORD'
  $dbUser = Get-ProjectEnvValue 'POSTGRES_USER'; if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = 'tiku' }
  $dbName = Get-ProjectEnvValue 'POSTGRES_DB'; if ([string]::IsNullOrWhiteSpace($dbName)) { $dbName = 'tikuzhushou' }
  if ([string]::IsNullOrWhiteSpace($dbPassword)) { throw 'POSTGRES_PASSWORD is required to export the database.' }
  $env:PGPASSWORD = $dbPassword
  & $PgDumpPath -w -h 127.0.0.1 -U $dbUser -d $dbName -Fc -f (Join-Path $data 'postgres.backup')
  if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
}

if (-not $SkipMinio) {
  $minioSource = Join-Path $projectRoot 'minio-data'
  if (-not (Test-Path $minioSource)) { throw "MinIO data directory missing: $minioSource" }
  # Run this while uploads are paused. The manifest records an explicit consistency warning.
  Copy-RequiredPath $minioSource (Join-Path $data 'minio-data')
}

Copy-RequiredPath (Join-Path $PSScriptRoot 'Import-DeliveryPackage.ps1') (Join-Path $output 'Import-DeliveryPackage.ps1')
$manifest = [ordered]@{
  packageFormat = 'tiku-delivery-v1'
  createdAt = (Get-Date).ToUniversalTime().ToString('o')
  sourceProject = $projectRoot
  frontendNodeModulesIncluded = [bool]$IncludeNodeModules
  databaseIncluded = -not [bool]$SkipDatabase
  minioIncluded = -not [bool]$SkipMinio
  redisIncluded = $false
  secretFilesIncluded = $false
  requiredEnv = @('DEEPSEEK_API_KEY','APP_BOOTSTRAP_ADMIN_PASSWORD','POSTGRES_USER','POSTGRES_PASSWORD','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD')
  restoreOrder = @('Install prerequisites','Create .env securely','Restore PostgreSQL','Restore MinIO data','Start services','Run health checks')
  minioConsistency = 'Source uploads must be paused while the raw MinIO data directory is copied.'
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
Set-Content -LiteralPath (Join-Path $secrets 'README.txt') -Encoding ascii -Value @(
  'This delivery package intentionally contains no secrets.',
  'Transfer .env separately through encrypted storage or a password manager.',
  'Create a new DeepSeek API key for the destination and do not reuse a key exposed in chat history.'
)
Write-Hashes $output
Compress-Archive -Path (Join-Path $output '*') -DestinationPath "$output.zip" -CompressionLevel Optimal
Write-Host "Delivery package created: $output"
Write-Host "Archive created: $output.zip"
