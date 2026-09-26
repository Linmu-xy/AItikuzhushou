[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$PackageDirectory,
  [Parameter(Mandatory = $true)][string]$TargetDirectory,
  [Parameter(Mandatory = $true)][string]$SecretsPath,
  [string]$PgRestorePath = 'C:\Program Files\PostgreSQL\17\bin\pg_restore.exe',
  [string]$CreatedbPath = 'C:\Program Files\PostgreSQL\17\bin\createdb.exe'
)

$ErrorActionPreference = 'Stop'
$package = [IO.Path]::GetFullPath($PackageDirectory)
$target = [IO.Path]::GetFullPath($TargetDirectory)
if (-not (Test-Path (Join-Path $package 'manifest.json'))) { throw 'manifest.json is missing.' }
if (Test-Path $target) { throw "Target directory already exists: $target. Import requires a new empty target." }
if (-not (Test-Path $SecretsPath)) { throw "Secrets file not found: $SecretsPath" }
if (-not (Test-Path $PgRestorePath) -or -not (Test-Path $CreatedbPath)) { throw 'PostgreSQL restore tools are missing.' }

function Read-Env([string]$Path, [string]$Name) {
  $line = Get-Content $Path | Where-Object { $_ -match ("^" + [regex]::Escape($Name) + "=(.*)$") } | Select-Object -First 1
  if ($line -and $line -match ("^" + [regex]::Escape($Name) + "=(.*)$")) { return $matches[1].Trim('"') }
  return $null
}

$manifest = Get-Content (Join-Path $package 'manifest.json') -Raw | ConvertFrom-Json
foreach ($required in $manifest.requiredEnv) {
  if ([string]::IsNullOrWhiteSpace((Read-Env $SecretsPath $required))) { throw "Secrets file is missing required variable: $required" }
}

# Verify every artifact before touching the target system.
foreach ($line in Get-Content (Join-Path $package 'checksums.sha256')) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $parts = $line -split '\s{2,}', 2
  if ($parts.Count -ne 2) { throw "Invalid checksum row: $line" }
  $actual = (Get-FileHash -LiteralPath (Join-Path $package $parts[1]) -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $parts[0]) { throw "Checksum mismatch: $($parts[1])" }
}

New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item -Path (Join-Path $package 'application\*') -Destination $target -Recurse -Force
Copy-Item -LiteralPath $SecretsPath -Destination (Join-Path $target '.env') -Force

$dbUser = Read-Env $SecretsPath 'POSTGRES_USER'; if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = 'tiku' }
$dbPassword = Read-Env $SecretsPath 'POSTGRES_PASSWORD'
$dbName = Read-Env $SecretsPath 'POSTGRES_DB'; if ([string]::IsNullOrWhiteSpace($dbName)) { $dbName = 'tikuzhushou' }
$env:PGPASSWORD = $dbPassword
if ($manifest.databaseIncluded) {
  & $CreatedbPath -w -h 127.0.0.1 -U $dbUser $dbName
  if ($LASTEXITCODE -ne 0) { throw "createdb failed for $dbName. Confirm that this is a fresh target database." }
  & $PgRestorePath -w -h 127.0.0.1 -U $dbUser -d $dbName (Join-Path $package 'data\postgres.backup')
  if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed.' }
}
if ($manifest.minioIncluded) {
  Copy-Item -LiteralPath (Join-Path $package 'data\minio-data') -Destination (Join-Path $target 'minio-data') -Recurse -Force
}

Write-Host "Import completed: $target"
Write-Host 'Install Java, Node.js, PostgreSQL, MinIO and Redis, then run scripts\Start-Tiku.ps1 -SkipFrontend.'
Write-Host 'For a production frontend, host frontend\dist through IIS or Nginx and proxy /api to port 8080.'
