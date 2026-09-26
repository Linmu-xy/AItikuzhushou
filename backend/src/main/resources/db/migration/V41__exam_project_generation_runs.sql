create table if not exists exam_project_generation_runs (
  id uuid primary key,
  project_id uuid not null references exam_projects(id) on delete cascade,
  generation_task_id uuid not null references exam_project_generation_tasks(id),
  evidence_snapshot_id uuid not null references exam_project_evidence_snapshots(id),
  status varchar(32) not null,
  generation_mode varchar(32) not null,
  planned_count integer not null,
  processed_count integer not null default 0,
  review_required_count integer not null default 0,
  review_pending_count integer not null default 0,
  failed_count integer not null default 0,
  request_json text not null,
  error_message varchar(2000),
  created_by uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  started_at timestamp with time zone,
  finished_at timestamp with time zone,
  updated_at timestamp with time zone not null,
  check (status in ('QUEUED','RUNNING','REVIEW_REQUIRED','REVIEW_PENDING','PARTIAL','FAILED','CANCELLED')),
  check (generation_mode in ('FAST','PROFESSIONAL_PRO')),
  check (planned_count > 0),
  check (processed_count >= 0),
  check (review_required_count >= 0),
  check (review_pending_count >= 0),
  check (failed_count >= 0)
);

create table if not exists exam_project_generation_items (
  id uuid primary key,
  run_id uuid not null references exam_project_generation_runs(id) on delete cascade,
  variant_item_id uuid not null references exam_project_variant_items(id),
  variant_no integer not null,
  variant_label varchar(16) not null,
  sequence_no integer not null,
  question_type varchar(80) not null,
  type_label varchar(160) not null,
  difficulty varchar(16) not null,
  points integer not null,
  status varchar(32) not null,
  attempts integer not null default 0,
  question_json text,
  design_json text not null,
  evidence_json text not null,
  review_json text,
  error_code varchar(64),
  error_message varchar(2000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  check (status in ('PLANNED','GENERATING','REVIEW_REQUIRED','REVIEW_PENDING','REJECTED','FAILED','APPROVED')),
  check (difficulty in ('EASY','MEDIUM','HARD')),
  check (points > 0),
  unique(run_id, variant_item_id),
  unique(run_id, variant_no, sequence_no)
);

create index if not exists idx_exam_project_generation_runs_project
  on exam_project_generation_runs(project_id, created_at desc);

create index if not exists idx_exam_project_generation_runs_status
  on exam_project_generation_runs(status, updated_at);

create index if not exists idx_exam_project_generation_items_run
  on exam_project_generation_items(run_id, variant_no, sequence_no);

