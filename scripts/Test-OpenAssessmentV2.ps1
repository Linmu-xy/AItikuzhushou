[CmdletBinding()]
param([ValidateSet('Create','ResumePlan','Status','Collect','RetryReview')][string]$Mode = 'Status', [ValidateRange(1,9)][int]$Revision = 1,
  [ValidateRange(1,5)][int]$QuestionCount = 3)

$ErrorActionPreference = 'Stop'
$assessmentRoot = Split-Path -Parent $PSScriptRoot
$assessmentOutput = Join-Path $assessmentRoot $(if ($Revision -eq 1) {'tmp/assessment-v2-live.json'} else {"tmp/assessment-v2-live-r$Revision.json"})
$assessmentEnv = @{}
Get-Content -LiteralPath (Join-Path $assessmentRoot '.env') | ForEach-Object {
  if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $assessmentEnv[$matches[1]] = $matches[2].Trim('"') }
}
$assessmentAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:' + $assessmentEnv['APP_BOOTSTRAP_ADMIN_PASSWORD']))
$assessmentHeaders = @{Authorization = 'Basic ' + $assessmentAuth}
function Request-Assessment([string]$Path, [string]$Method = 'GET', $Body = $null) {
  $requestArgs = @{ Uri = "http://127.0.0.1:8080/api$Path"; Method = $Method; Headers = $assessmentHeaders; TimeoutSec = 600 }
  if ($null -ne $Body) { $requestArgs.ContentType = 'application/json; charset=utf-8'; $requestArgs.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
  Invoke-RestMethod @requestArgs
}
if ($Mode -in @('Create','ResumePlan')) {
  if ($Mode -eq 'Create' -and (Test-Path -LiteralPath $assessmentOutput)) { throw 'A benchmark already exists. Inspect it instead of creating duplicate paid calls.' }
  $baseline = Request-Assessment '/exam-projects/fc9e2cad-eee3-48b8-8b82-ee2cf637038e'
  if ($Mode -eq 'Create') {
  $project = Request-Assessment '/exam-projects' 'POST' @{
    name = "CAD能力导向V2-$($QuestionCount)题实测-20260926-r$Revision"; knowledgeBaseId = $baseline.knowledgeBaseId
    mode = 'KNOWLEDGE_BASE'; requirementText = $baseline.requirementText; variantCount = 1
    difficultyProfile = ($baseline.difficultyProfileJson | ConvertFrom-Json)
    scoringStructure = @(@{type='智能出题'; count=$QuestionCount; points=5})
    authorizationConfirmed = $true; webSearchEnabled = $true
  }
  $state = @{projectId = $project.id; startedAt = [DateTime]::UtcNow.ToString('o'); baselineRunId = 'e79709be-3fb2-4cd0-8f2d-5f9dfdc3af08'}
  $state | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath $assessmentOutput -Encoding utf8
  foreach ($source in $baseline.sources | Where-Object enabled) {
    Request-Assessment "/exam-projects/$($project.id)/sources" 'POST' @{
      sourceId = $source.sourceId; sourceType = $source.sourceType; sourceRole = $source.sourceRole
    } | Out-Null
  }
  } else {
    $state = Get-Content -LiteralPath $assessmentOutput -Raw | ConvertFrom-Json -AsHashtable
    if ($state.runId -or $state.plan) { throw 'A plan/run already exists; use Status or Collect.' }
    $project = Request-Assessment "/exam-projects/$($state.projectId)" 'PATCH' @{
      scoringStructure = @($baseline.scoringStructureJson | ConvertFrom-Json)
    }
  }
  $prepared = Request-Assessment "/exam-projects/$($project.id)/generation-preparation" 'POST'
  if ($prepared.status -ne 'READY') { throw ($prepared.blockers -join '; ') }
  Write-Output "Created test project $($project.id); planning started."
  $plan = Request-Assessment "/exam-projects/$($project.id)/variant-generation-tasks" 'POST'
  $state.plan = $plan
  $state | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath $assessmentOutput -Encoding utf8
  if ($plan.assessmentPlan.pipelineVersion -ne 'OPEN_ASSESSMENT_V2') { throw 'Server is not running the V2 pipeline' }
  $run = Request-Assessment "/exam-projects/$($project.id)/variant-generation-tasks/$($plan.id)/runs" 'POST' @{generationMode='FAST'}
  $state.runId = $run.id
  $state | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath $assessmentOutput -Encoding utf8
  Write-Output "Started V2 generation run $($run.id)"
} else {
  $state = Get-Content -LiteralPath $assessmentOutput -Raw | ConvertFrom-Json -AsHashtable
  if (!$state.runId) { Write-Output "Planning in progress: project $($state.projectId)"; exit 0 }
  $run = Request-Assessment "/exam-projects/$($state.projectId)/variant-generation-runs/$($state.runId)"
  if ($Mode -eq 'RetryReview') {
    if ($run.status -in @('QUEUED','RUNNING')) { throw 'Wait for generation to finish before reviewing saved candidates.' }
    $state.reviewHistory = @($state.reviewHistory) + @($run)
    $state | ConvertTo-Json -Depth 35 | Set-Content -LiteralPath $assessmentOutput -Encoding utf8
    foreach ($item in $run.items | Where-Object { !$_.reviewerId -and $_.questionVersion -eq 1 -and ($_.status -eq 'REVIEW_PENDING' -or ($_.status -eq 'REJECTED' -and $_.errorCode -eq 'QUALITY_REJECTED')) }) {
      Write-Output "Reviewing saved item $($item.sequenceNo), without regeneration or manual approval."
      $null = Request-Assessment "/exam-projects/$($state.projectId)/variant-generation-runs/$($state.runId)/items/$($item.id)/retry-review" 'POST'
    }
    $run = Request-Assessment "/exam-projects/$($state.projectId)/variant-generation-runs/$($state.runId)"
  }
  $run | Select-Object id,status,plannedCount,processedCount,reviewRequiredCount,reviewPendingCount,failedCount,errorMessage | ConvertTo-Json
  $run.items | Select-Object sequenceNo,status,attempts,errorMessage | ConvertTo-Json
  if ($Mode -in @('Collect','RetryReview')) {
    $state.run = $run
    $state.collectedAt = [DateTime]::UtcNow.ToString('o')
    $state | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $assessmentOutput -Encoding utf8
    $run.items | Select-Object sequenceNo,status,question,review | ConvertTo-Json -Depth 12
  }
}
