[CmdletBinding()]
param(
  [switch]$Build,
  [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $projectRoot 'backend'
$frontend = Join-Path $projectRoot 'frontend'
$logsDir = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force $logsDir | Out-Null
$javaPreferred = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
$mavenPreferred = 'D:\code\tools\apache-maven-3.9.16\bin\mvn.cmd'
$nodePreferred = 'C:\Users\lxy17\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
$minioPreferred = 'C:\Users\lxy17\AppData\Local\Microsoft\WinGet\Packages\MinIO.Server_Microsoft.Winget.Source_8wekyb3d8bbwe\minio.exe'
$redisPreferred = 'C:\Users\lxy17\AppData\Local\Microsoft\WinGet\Packages\taizod1024.redis-windows-fork_Microsoft.Winget.Source_8wekyb3d8bbwe\Redis-8.10.1-Windows-x64-msys2\redis-server.exe'
$psqlPreferred = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'

function Resolve-Executable([string]$Preferred, [string]$Command, [bool]$Required = $true) {
  if (Test-Path $Preferred) { return $Preferred }
  $found = Get-Command $Command -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($found) { return $found.Source }
  if ($Required) { throw "Executable not found: $Command" }
  return $null
}

$java = Resolve-Executable $javaPreferred 'java'
$node = Resolve-Executable $nodePreferred 'node'
$minio = Resolve-Executable $minioPreferred 'minio'
$psql = Resolve-Executable $psqlPreferred 'psql'
$maven = Resolve-Executable $mavenPreferred 'mvn' $false
$redisServer = Resolve-Executable $redisPreferred 'redis-server'

function Test-Listening([int]$Port) {
  return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing environment file: $envFile" }
$loadedEnv = @{}
foreach ($line in [IO.File]::ReadAllLines($envFile)) {
  $value = $line.Trim().TrimStart([char]0xFEFF)
  if ($value.Length -eq 0 -or $value.StartsWith('#')) { continue }
  $match = [regex]::Match($value, '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$')
  if (-not $match.Success) { continue }
  $loadedEnv[$match.Groups[1].Value] = $match.Groups[2].Value.Trim('"')
}
# Keep the bootstrap secret resilient to legacy .env encodings used by older
# Windows editors. The generic parser above handles normal files; this direct
# lookup guarantees the startup credential is retained on every supported host.
if (-not $loadedEnv.ContainsKey('APP_BOOTSTRAP_ADMIN_PASSWORD')) {
  $bootstrapLine = [IO.File]::ReadAllLines($envFile) | Where-Object { $_.Trim().TrimStart([char]0xFEFF) -like 'APP_BOOTSTRAP_ADMIN_PASSWORD=*' } | Select-Object -First 1
  if ($bootstrapLine) { $loadedEnv['APP_BOOTSTRAP_ADMIN_PASSWORD'] = ($bootstrapLine -replace '^\s*APP_BOOTSTRAP_ADMIN_PASSWORD=', '').Trim('"') }
}
foreach ($entry in $loadedEnv.GetEnumerator()) {
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($entry.Key, 'Process'))) {
    Set-Item -Path ("Env:" + $entry.Key) -Value $entry.Value
  }
}
foreach($required in 'POSTGRES_PASSWORD','APP_BOOTSTRAP_ADMIN_PASSWORD','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD') {
  if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($required, 'Process'))) {
    throw "Missing process environment variable $required. Set it and retry."
  }
}
# Production must keep a fixed APP_CONFIG_ENCRYPTION_KEY (>=16 chars): it encrypts the
# admin-managed DeepSeek key at rest. Changing or losing it makes the stored credential
# undecryptable and forces a re-save from the admin console.
$encKey = [Environment]::GetEnvironmentVariable('APP_CONFIG_ENCRYPTION_KEY', 'Process')
if([string]::IsNullOrWhiteSpace($encKey) -or $encKey.Trim().Length -lt 16) {
  throw 'APP_CONFIG_ENCRYPTION_KEY must be set to at least 16 characters (fixed once) before production startup. Add it to .env or the server environment, then retry.'
}

$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:JDBC_DATABASE_URL = if($env:JDBC_DATABASE_URL){$env:JDBC_DATABASE_URL}else{'jdbc:postgresql://127.0.0.1:5432/tikuzhushou'}
$env:POSTGRES_USER = if($env:POSTGRES_USER){$env:POSTGRES_USER}else{'tiku'}
$env:MINIO_ENDPOINT = if($env:MINIO_ENDPOINT){$env:MINIO_ENDPOINT}else{'http://127.0.0.1:9000'}
$env:APP_STORAGE_PROVIDER = 'minio'
$env:APP_STORAGE_PATH = Join-Path $backend 'storage-prod'
# Logback writes rolling application logs under LOG_PATH; the Start-Process
# redirections below capture any remaining console output to the same folder.
$env:LOG_PATH = $logsDir

if(-not (Get-Service -Name 'postgresql-x64-17' -ErrorAction SilentlyContinue)) { throw 'PostgreSQL 17 service not found. Install it first.' }
if((Get-Service postgresql-x64-17).Status -ne 'Running') { Start-Service postgresql-x64-17 }
if(-not (Test-Path $psql)) { throw "PostgreSQL client not found: $psql" }
$env:PGPASSWORD=$env:POSTGRES_PASSWORD
& $psql -w -h 127.0.0.1 -U $env:POSTGRES_USER -d tikuzhushou -c 'select 1' | Out-Null
if($LASTEXITCODE -ne 0) { throw 'PostgreSQL credentials are invalid. Check environment variables or .env.' }

if(-not (Test-Listening 9000)) {
  $data = Join-Path $projectRoot 'minio-data'
  New-Item -ItemType Directory -Force $data | Out-Null
  Start-Process -FilePath $minio -ArgumentList 'server',$data,'--address','127.0.0.1:9000','--console-address','127.0.0.1:9001' -WindowStyle Hidden
  # First-run formatting can take longer than two seconds on ordinary Windows disks.
  $minioDeadline = (Get-Date).AddSeconds(20)
  while(-not (Test-Listening 9000) -and (Get-Date) -lt $minioDeadline) { Start-Sleep -Milliseconds 500 }
}
if(-not (Test-Listening 9000)) { throw 'MinIO failed to listen on port 9000.' }

if(-not (Test-Listening 6379)) {
  $redisData = Join-Path $projectRoot 'redis-data'
  New-Item -ItemType Directory -Force $redisData | Out-Null
  Start-Process -FilePath $redisServer -ArgumentList '--bind','127.0.0.1','--port','6379','--appendonly','yes','--dir',$redisData -WindowStyle Hidden
  Start-Sleep -Seconds 2
}
if(-not (Test-Listening 6379)) { throw 'Redis failed to listen on port 6379.' }

if($Build) {
  if ([string]::IsNullOrWhiteSpace($maven)) { throw 'Maven is required when -Build is specified.' }
  Push-Location $backend; try {
    & $maven -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed with exit code $LASTEXITCODE." }
  } finally { Pop-Location }
  if(-not $SkipFrontend) { Push-Location $frontend; try { & $node 'node_modules\vite\bin\vite.js' build } finally { Pop-Location } }
}
if(-not (Test-Listening 8080)) {
  Start-Process -FilePath $java -ArgumentList '-jar',(Join-Path $backend 'target\tiku-api-0.1.0.jar') `
    -WorkingDirectory $backend `
    -RedirectStandardOutput (Join-Path $logsDir 'tiku-api.out.log') `
    -RedirectStandardError (Join-Path $logsDir 'tiku-api.err.log') `
    -WindowStyle Hidden
}
if(-not $SkipFrontend -and -not (Test-Listening 4173)) {
  Start-Process -FilePath $node `
    -ArgumentList (Join-Path $frontend 'node_modules\vite\bin\vite.js'),'--host','127.0.0.1','--port','4173' `
    -WorkingDirectory $frontend `
    -RedirectStandardOutput (Join-Path $logsDir 'frontend.out.log') `
    -RedirectStandardError (Join-Path $logsDir 'frontend.err.log') `
    -WindowStyle Hidden
}

$deadline=(Get-Date).AddSeconds(30)
while(-not (Test-Listening 8080) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
if(-not (Test-Listening 8080)) { throw 'Backend did not start within 30 seconds. Check backend logs.' }
& (Join-Path $PSScriptRoot 'Test-Tiku.ps1')
