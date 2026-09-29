$ErrorActionPreference = 'Stop'
$evalRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evalMaven = $env:TIKU_MAVEN_CMD
if (!$evalMaven) {
  $evalInstalled = Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if ($evalInstalled) { $evalMaven = $evalInstalled.Source }
  else { $evalMaven = Join-Path (Split-Path -Parent $evalRepo) 'tools/apache-maven-3.9.16/bin/mvn.cmd' }
}
if (!(Test-Path -LiteralPath $evalMaven)) { throw 'Set TIKU_MAVEN_CMD to your Maven executable.' }
Push-Location (Join-Path $evalRepo 'backend')
try {
  & $evalMaven -q '-Dtest=AssessmentRegressionFixtureTests' test
  exit $LASTEXITCODE
} finally { Pop-Location }
