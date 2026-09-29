[CmdletBinding()]
param(
  [ValidateSet('Create','Status','Collect','RetryRejectedReview')][string]$Mode = 'Status',
  [ValidateRange(1,9)][int]$Revision = 1
)

$ErrorActionPreference = 'Stop'
$benchmarkRoot = Split-Path -Parent $PSScriptRoot
$benchmarkFile = Join-Path $benchmarkRoot "tmp/assessment-optimized-r$Revision.json"
$benchmarkBaseline = Get-Content -LiteralPath (Join-Path $benchmarkRoot 'tmp/assessment-v2-live-r4.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$benchmarkConfig = @{}
Get-Content -LiteralPath (Join-Path $benchmarkRoot '.env') | ForEach-Object {
  if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $benchmarkConfig[$matches[1]] = $matches[2].Trim('"') }
}
$benchmarkHeaders = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:' + $benchmarkConfig['APP_BOOTSTRAP_ADMIN_PASSWORD'])) }
function Invoke-Benchmark([string]$Path, [string]$Method='GET', $Body=$null) {
  $request = @{ Uri="http://127.0.0.1:8080/api$Path"; Method=$Method; Headers=$benchmarkHeaders; TimeoutSec=60 }
  if ($null -ne $Body) { $request.ContentType='application/json; charset=utf-8'; $request.Body=$Body | ConvertTo-Json -Depth 20 -Compress }
  Invoke-RestMethod @request
}

if ($Mode -eq 'Create') {
  if (Test-Path -LiteralPath $benchmarkFile) { throw 'This paid benchmark already exists. Inspect it; do not repeat it automatically.' }
  $benchmarkProject = [guid]$benchmarkBaseline.projectId
  $benchmarkTask = [guid]$benchmarkBaseline.plan.id
  if ($benchmarkBaseline.plan.assessmentPlan.pipelineVersion -ne 'OPEN_ASSESSMENT_V2') { throw 'Baseline is not V2' }
  $state = @{
    projectId=$benchmarkProject; taskId=$benchmarkTask; baselineRunId=$benchmarkBaseline.runId
    startedAt=[DateTime]::UtcNow.ToString('o'); generationMode='FAST'; planningReused=$true
  }
  # Freeze the baseline plan: no new planning/visual-survey fees, no changes to previous questions.
  # Write the marker before dispatch so an ambiguous network failure cannot silently create a duplicate run.
  $state | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $benchmarkFile -Encoding UTF8
  $run = Invoke-Benchmark "/exam-projects/$benchmarkProject/variant-generation-tasks/$benchmarkTask/runs" 'POST' @{generationMode='FAST'}
  $state.runId=$run.id
  $state | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $benchmarkFile -Encoding UTF8
  Write-Output "Started frozen-plan comparison: $($run.id)"
} else {
  $state = Get-Content -LiteralPath $benchmarkFile -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
  if (!$state.runId) { throw 'Dispatch result is uncertain: inspect existing project runs before any retry.' }
  $run = Invoke-Benchmark "/exam-projects/$($state.projectId)/variant-generation-runs/$($state.runId)"
  if ($Mode -eq 'RetryRejectedReview') {
    if ($state.reviewRetryStartedAt) { throw 'Review-only paid retest already dispatched; inspect the run instead of repeating it.' }
    $rejected = @($run.items | Where-Object { $_.status -eq 'REJECTED' -and $_.questionVersion -eq 1 -and !$_.reviewerId })
    if ($rejected.Count -ne 1) { throw 'Expected exactly one untouched rejected candidate; no requests sent.' }
    $state.beforeReviewRetry=$run
    $state.reviewRetryStartedAt=[DateTime]::UtcNow.ToString('o')
    $state.reviewRetryItemId=$rejected[0].id
    $state | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $benchmarkFile -Encoding UTF8
    # One review-only operation, no generation or human approval. Preserve the original run above.
    $requestPath="/exam-projects/$($state.projectId)/variant-generation-runs/$($state.runId)/items/$($rejected[0].id)/retry-review"
    $run = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api$requestPath" -Method Post -Headers $benchmarkHeaders -TimeoutSec 300
    $state.afterReviewRetry=$run
    $state.reviewRetryFinishedAt=[DateTime]::UtcNow.ToString('o')
    $state | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $benchmarkFile -Encoding UTF8
  }
  $run | Select-Object id,status,plannedCount,processedCount,reviewRequiredCount,reviewPendingCount,failedCount,startedAt,finishedAt | ConvertTo-Json
  $run.items | Select-Object sequenceNo,status,attempts,errorMessage | ConvertTo-Json
  if ($Mode -eq 'Collect') {
    $state.run=$run; $state.collectedAt=[DateTime]::UtcNow.ToString('o')
    $state | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $benchmarkFile -Encoding UTF8
  }
}
