create table if not exists exam_project_export_runs (
  id uuid primary key,
  project_id uuid not null references exam_projects(id) on delete cascade,
  generation_run_id uuid not null references exam_project_generation_runs(id) on delete cascade,
  evidence_snapshot_id uuid not null references exam_project_evidence_snapshots(id),
  status varchar(24) not null,
  output_types_json text not null,
  requested_count integer not null,
  completed_count integer not null default 0,
  error_message varchar(2000),
  created_by uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  started_at timestamp with time zone,
  finished_at timestamp with time zone,
  updated_at timestamp with time zone not null,
  check (status in ('QUEUED','RUNNING','DOWNLOAD_READY','FAILED','CANCELLED')),
  check (requested_count > 0),
  check (completed_count >= 0)
);

create index if not exists idx_exam_project_export_runs_project
  on exam_project_export_runs(project_id, created_at desc);
create index if not exists idx_exam_project_export_runs_queue
  on exam_project_export_runs(status, updated_at);

create table if not exists exam_project_export_artifacts (
  id uuid primary key,
  export_run_id uuid not null references exam_project_export_runs(id) on delete cascade,
  output_type varchar(32) not null,
  filename varchar(255) not null,
  media_type varchar(160) not null,
  storage_path varchar(700) not null,
  size_bytes bigint not null,
  sha256 varchar(64) not null,
  created_at timestamp with time zone not null,
  unique(export_run_id, output_type)
);

create index if not exists idx_exam_project_export_artifacts_run
  on exam_project_export_artifacts(export_run_id, created_at);
