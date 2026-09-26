[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$checks = @(
  @{ Name='PostgreSQL service'; Passed=((Get-Service -Name 'postgresql-x64-17' -ErrorAction SilentlyContinue).Status -eq 'Running') },
  @{ Name='MinIO health'; Passed=((Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:9000/minio/health/live' -TimeoutSec 5).StatusCode -eq 200) },
  @{ Name='Redis TCP'; Passed=($null -ne (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)) },
  @{ Name='Backend health'; Passed=((Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8080/api/health' -TimeoutSec 5).StatusCode -eq 200) },
  @{ Name='Frontend'; Passed=((Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:4173/' -TimeoutSec 5).StatusCode -eq 200) }
)
$checks | Format-Table -AutoSize
if($checks.Where({-not $_.Passed}).Count -gt 0) { exit 1 }
