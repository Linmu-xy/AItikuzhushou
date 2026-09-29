[CmdletBinding()]
param([ValidateSet('Smoke','Seed','Workflow','AiPoints','AiRevision')][string]$Mode = 'Smoke')
$ErrorActionPreference='Stop'
$qaRoot=Split-Path -Parent $PSScriptRoot
$qaConfig=@{}
Get-Content (Join-Path $qaRoot '.env') | ForEach-Object { if($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {$qaConfig[$Matches[1]]=$Matches[2].Trim('"')} }
$qaHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:'+$qaConfig['APP_BOOTSTRAP_ADMIN_PASSWORD']))}
function Call-Api([string]$path,[string]$method='GET',$body=$null) {
  $args=@{Uri=('http://127.0.0.1:8080'+$path);Method=$method;Headers=$qaHeaders;TimeoutSec=600}
  if($null-ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=[Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 30 -Compress))}
  $result=Invoke-RestMethod @args
  if($result -is [array]) {foreach($row in $result){$row}} else {$result}
}
function Expect([bool]$condition,[string]$label){if(-not $condition){throw "FAIL: $label"};Write-Output "PASS: $label"}
function Expect-Rejection([scriptblock]$work,[string]$label){try{& $work|Out-Null}catch{if([int]$_.Exception.Response.StatusCode -in 400,403,409,503){Write-Output "PASS: $label";return};throw};throw "FAIL: $label was accepted"}
$qaStatePath=Join-Path $qaRoot 'tmp/quality-maintenance-qa.json'
if($Mode -eq 'Seed') {
  if(Test-Path $qaStatePath){Get-Content $qaStatePath;exit}
  $qaBase=Call-Api '/api/knowledge-bases' 'POST' @{name='工作台验收 · 知识维护';description='独立测试数据，可移入回收站。'}
  $qaProject=[guid]::NewGuid();$qaSnapshot=[guid]::NewGuid();$qaTask=[guid]::NewGuid();$qaRun=[guid]::NewGuid()
  $env:PGPASSWORD=$qaConfig['POSTGRES_PASSWORD'];$qaDbUser=if($qaConfig['POSTGRES_USER']){$qaConfig['POSTGRES_USER']}else{'tiku'}
  # Clone the existing CAD test batch into an explicitly named QA project. Never update the source.
  $sql=@"
begin;
insert into exam_projects(id,owner_id,knowledge_base_id,name,mode,status,requirement_text,variant_count,difficulty_profile_json,scoring_structure_json,authorization_confirmed,web_search_enabled,created_at,updated_at)
select '$qaProject',owner_id,knowledge_base_id,'工作台验收 · CAD 审核流程',mode,status,requirement_text,variant_count,difficulty_profile_json,scoring_structure_json,authorization_confirmed,web_search_enabled,now(),now() from exam_projects where id='8c526b64-3c36-43bc-a51a-565958aa898f';
insert into exam_project_sources(id,project_id,source_type,source_document_id,cad_material_id,source_role,version_ref,enabled,created_at)
select gen_random_uuid(),'$qaProject',source_type,source_document_id,cad_material_id,source_role,version_ref,enabled,now() from exam_project_sources where project_id='8c526b64-3c36-43bc-a51a-565958aa898f';
insert into exam_project_evidence_snapshots(id,project_id,snapshot_version,status,blockers_json,sources_json,facts_json,profiles_json,snapshot_hash,created_by,created_at)
select '$qaSnapshot','$qaProject',1,status,blockers_json,sources_json,facts_json,profiles_json,snapshot_hash,created_by,now() from exam_project_evidence_snapshots where id=(select evidence_snapshot_id from exam_project_generation_runs where id='cc10a133-dc3b-4271-b17d-efaf110fd41c');
insert into exam_project_generation_tasks(id,project_id,evidence_snapshot_id,status,variant_count,question_count_per_variant,total_question_count,request_json,created_by,created_at,updated_at)
select '$qaTask','$qaProject','$qaSnapshot','READY',variant_count,question_count_per_variant,total_question_count,request_json,created_by,now(),now() from exam_project_generation_tasks where id=(select generation_task_id from exam_project_generation_runs where id='cc10a133-dc3b-4271-b17d-efaf110fd41c');
insert into exam_project_variant_items(id,generation_task_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,source_roles_json,status,created_at)
select gen_random_uuid(),'$qaTask',variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,source_roles_json,status,now() from exam_project_variant_items where generation_task_id=(select generation_task_id from exam_project_generation_runs where id='cc10a133-dc3b-4271-b17d-efaf110fd41c');
insert into exam_project_generation_runs(id,project_id,generation_task_id,evidence_snapshot_id,status,generation_mode,planned_count,processed_count,review_required_count,review_pending_count,failed_count,request_json,created_by,created_at,finished_at,updated_at)
select '$qaRun','$qaProject','$qaTask','$qaSnapshot','REVIEW_REQUIRED',generation_mode,planned_count,processed_count,planned_count,0,0,request_json,created_by,now(),now(),now() from exam_project_generation_runs where id='cc10a133-dc3b-4271-b17d-efaf110fd41c';
insert into exam_project_generation_items(id,run_id,variant_item_id,variant_no,variant_label,sequence_no,question_type,type_label,difficulty,points,status,attempts,question_json,design_json,evidence_json,review_json,created_at,updated_at)
select gen_random_uuid(),'$qaRun',v.id,i.variant_no,i.variant_label,i.sequence_no,i.question_type,i.type_label,i.difficulty,i.points,'REVIEW_REQUIRED',1,i.question_json,i.design_json,i.evidence_json,i.review_json,now(),now() from exam_project_generation_items i join exam_project_variant_items v on v.generation_task_id='$qaTask' and v.variant_no=i.variant_no and v.sequence_no=i.sequence_no where i.run_id='cc10a133-dc3b-4271-b17d-efaf110fd41c';
commit;
"@
  $sql | & 'C:/Program Files/PostgreSQL/17/bin/psql.exe' -w -h 127.0.0.1 -U $qaDbUser -d tikuzhushou -v ON_ERROR_STOP=1 | Out-Null
  if($LASTEXITCODE-ne 0){throw 'QA fixture creation failed'}
  @{baseId=$qaBase.id;projectId="$qaProject";runId="$qaRun"}|ConvertTo-Json|Set-Content -LiteralPath $qaStatePath -Encoding utf8
  Get-Content $qaStatePath;exit
}
if(-not(Test-Path $qaStatePath)){throw 'Run -Mode Seed first'}
$qaState=Get-Content -Raw $qaStatePath|ConvertFrom-Json
$pointsPath='/api/knowledge-bases/'+$qaState.baseId+'/points'
$runPath='/api/exam-projects/'+$qaState.projectId+'/variant-generation-runs/'+$qaState.runId
if($Mode -eq 'Workflow') {
  $run=Call-Api $runPath;$item=@($run.items|Where-Object {$_.sequenceNo-eq 2})[0]
  if($item.status-eq 'APPROVED'){Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='REOPEN';expectedVersion=$item.questionVersion}|Out-Null}
  $question=@{stem='验收用模拟题：平面图比例为1:50，图上矩形长40 mm、宽24 mm。（1）求实物长宽，以m表示；（2）求实物面积；（3）解释把图上面积乘50为什么不正确。';answer='实物长2 m、宽1.2 m，面积2.4 m²。长度放大50倍时，面积应放大50²=2500倍。';analysis='统一毫米和米的单位后，长40×50/1000=2，宽24×50/1000=1.2，面积为2×1.2=2.4。面积涉及两个方向的长度，比例须平方。';scoringItems=@(@{criterion='实物长正确2分，宽正确2分，单位正确1分';points=5},@{criterion='面积公式正确2分，数值正确2分，面积单位正确1分';points=5},@{criterion='说明两个方向同时缩放4分，指出面积比为2500倍1分';points=5});qualityChecklist=@{target=$true;answer=$true;fairness=$true;scoring=$true}}
  $saved=Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='SAVE_DRAFT';expectedVersion=$item.questionVersion;question=$question;comment='独立验收项目：替换为可核算的流程测试题，不用于实际考试。'}
  Expect ($saved.questionVersion-eq ($item.questionVersion+1)) 'Save question and increment version'
  Expect-Rejection {Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='SAVE_DRAFT';expectedVersion=$item.questionVersion;question=$question}} 'Reject stale question edit'
  $approved=Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='APPROVE';expectedVersion=$saved.questionVersion;comment='核对示例运算与分值，仅验证流程。'}
  Expect ($approved.status-eq 'APPROVED') 'Approve V2 question without source requirement'
  Expect-Rejection {Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='SAVE_DRAFT';expectedVersion=$saved.questionVersion;question=$question}} 'Lock approved question'
  $reopened=Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='REOPEN';expectedVersion=$approved.questionVersion}
  Expect ($reopened.status-eq 'REVIEW_REQUIRED') 'Reopen approved question'
  $rejected=Call-Api "$runPath/items/$($item.id)/review" 'PATCH' @{decision='REJECT';expectedVersion=$reopened.questionVersion;comment='流程验收完成：此题只用于测试，保留但不用于真实考试。'}
  Expect ($rejected.status-eq 'REJECTED') 'Reject with reason'
  Expect (@(Call-Api "$runPath/items/$($item.id)/versions").Count-ge 2) 'Question versions retained'
  Expect (@(Call-Api "$runPath/items/$($item.id)/review-events").Count-ge 4) 'Review audit retained'
  Expect (-not (Call-Api "$runPath/export-readiness").ready) 'Block export of unapproved batch'
  exit
}
if($Mode -eq 'AiPoints') {
  $cadBase='98ddb0cc-7767-4f6a-869d-b8836e908c25'
  $qaDocs=@(Call-Api "/api/knowledge-bases/$cadBase/documents" | Where-Object {$_.status -in 'PARSED','PARSED_PARTIAL'})
  $before=@(Call-Api "/api/knowledge-bases/$cadBase/points").Count
  $result=Call-Api "/api/knowledge-bases/$cadBase/points/suggestions" 'POST' @{documentIds=@($qaDocs[0].id)}
  $result|ConvertTo-Json -Depth 20|Set-Content (Join-Path $qaRoot 'tmp/quality-ai-points.json') -Encoding utf8
  Expect ($before -eq @(Call-Api "/api/knowledge-bases/$cadBase/points").Count) 'AI suggestions do not persist automatically'
  Write-Output "AI suggestions: $(@($result.points).Count)";exit
}
if($Mode -eq 'AiRevision') {
  $run=Call-Api $runPath;$item=@($run.items|Where-Object {$_.status-eq 'REVIEW_REQUIRED'})[0]
  $before=$item.question|ConvertTo-Json -Depth 20 -Compress
  $result=Call-Api "$runPath/items/$($item.id)/revision-preview" 'POST' @{expectedVersion=$item.questionVersion;instruction='审查题干中的量、单位和基准是否明确，消除可能导致不同公式的歧义。保持考核能力与分值，评分规则接受合理替代解法，优先保留正确部分。'}
  $result|ConvertTo-Json -Depth 30|Set-Content (Join-Path $qaRoot 'tmp/quality-ai-revision.json') -Encoding utf8
  $after=@((Call-Api $runPath).items|Where-Object {$_.id-eq $item.id})[0]
  Expect (($after.question|ConvertTo-Json -Depth 20 -Compress)-eq $before) 'AI revision leaves original unchanged'
  Write-Output "AI review available: $($result.review.available), passed: $($result.review.passed)";exit
}
$draft=@{title='验收：比例与单位换算';chapter='工程制图';description='图纸比例是图上长度与实际长度的比值，计算前统一单位。';objective='能在新情境中计算真实尺寸并解释比例关系。';misconceptions='把长度比误用于面积比。';status='DRAFT';origin='MANUAL';documentIds=@()}
$point=Call-Api $pointsPath 'POST' $draft
Expect ($point.version-eq 1) 'Create knowledge draft'
$draft.status='CONFIRMED';$draft.expectedVersion=1
$point=Call-Api "$pointsPath/$($point.id)" 'PUT' $draft
Expect ($point.version-eq 2) 'Confirm knowledge point'
Expect-Rejection {Call-Api "$pointsPath/$($point.id)" 'PUT' $draft} 'Reject stale knowledge edit'
$draft.status='ARCHIVED';$draft.expectedVersion=2;$point=Call-Api "$pointsPath/$($point.id)" 'PUT' $draft
$draft.status='CONFIRMED';$draft.expectedVersion=3;$point=Call-Api "$pointsPath/$($point.id)" 'PUT' $draft
Expect ($point.version-eq 4) 'Archive and restore point'
Expect (@(Call-Api "$pointsPath/$($point.id)/versions").Count-eq 4) 'Knowledge version history'
$inspection=@(Call-Api "$runPath/quality")
Expect ($inspection.Count-ge 1) 'Quality inspection endpoint'
$run=Call-Api $runPath;$item=@($run.items|Where-Object {$_.status-eq 'REVIEW_REQUIRED'})[0]
$bad=@{decision='APPROVE';expectedVersion=$item.questionVersion;comment='验收评分总分保护';question=@{scoringItems=@(@{criterion='评分点';points=999})}}
Expect-Rejection {Call-Api "$runPath/items/$($item.id)/review" 'PATCH' $bad} 'Block incorrect scoring sum'
Write-Output "QA project: $($qaState.projectId)"
