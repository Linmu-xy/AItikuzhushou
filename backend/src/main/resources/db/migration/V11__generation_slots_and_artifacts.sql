alter table workflow_tasks add column if not exists result_json text;

create table if not exists generation_job_items (
  id uuid primary key,
  job_id uuid not null references generation_jobs(id) on delete cascade,
  sequence_no integer not null,
  question_type varchar(48) not null,
  difficulty varchar(24),
  assessment_point text not null,
  status varchar(24) not null,
  attempts integer not null default 0,
  question_json text,
  error_code varchar(64),
  error_message varchar(1000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(job_id, sequence_no)
);
create index if not exists idx_generation_items_job_status on generation_job_items(job_id,status,sequence_no);

create table if not exists export_artifacts (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  source_job_id uuid not null references generation_jobs(id),
  task_id uuid not null references workflow_tasks(id),
  storage_path text not null,
  filename varchar(255) not null,
  media_type varchar(160) not null,
  size_bytes bigint not null,
  expected_total integer not null,
  actual_total integer not null,
  partial boolean not null,
  created_at timestamp with time zone not null,
  unique(task_id)
);
create index if not exists idx_export_artifacts_owner_created on export_artifacts(owner_id,created_at desc);
