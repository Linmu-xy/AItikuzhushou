create table if not exists fast_generation_jobs (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  document_id uuid not null references source_documents(id),
  status varchar(32) not null,
  density varchar(16) not null,
  question_types_json text not null,
  requested_questions integer not null default 0,
  generated_questions integer not null default 0,
  failed_questions integer not null default 0,
  processed_segments integer not null default 0,
  total_segments integer not null default 0,
  request_json text not null,
  error_message varchar(2000),
  idempotency_key varchar(120),
  created_at timestamp with time zone not null,
  started_at timestamp with time zone,
  finished_at timestamp with time zone,
  updated_at timestamp with time zone not null,
  check (status in ('QUEUED','RUNNING','READY_FOR_REVIEW','PARTIAL_SUCCESS','FAILED','CANCELLED')),
  check (density in ('LOW','HIGH')),
  check (requested_questions >= 0),
  check (generated_questions >= 0),
  check (failed_questions >= 0),
  check (processed_segments >= 0),
  check (total_segments >= 0),
  unique(owner_id, idempotency_key)
);

create index if not exists idx_fast_generation_jobs_owner on fast_generation_jobs(owner_id, updated_at desc);
create index if not exists idx_fast_generation_jobs_status on fast_generation_jobs(status, updated_at);

create table if not exists fast_generation_segments (
  id uuid primary key,
  job_id uuid not null references fast_generation_jobs(id) on delete cascade,
  sequence_no integer not null,
  source_ref varchar(255) not null,
  source_excerpt text not null,
  content_hash varchar(64) not null,
  planned_questions integer not null default 0,
  generated_questions integer not null default 0,
  status varchar(24) not null,
  attempts integer not null default 0,
  error_message varchar(2000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(job_id, sequence_no),
  check (status in ('PLANNED','RUNNING','SUCCEEDED','FAILED'))
);

create index if not exists idx_fast_generation_segments_job on fast_generation_segments(job_id, sequence_no);

create table if not exists fast_generation_items (
  id uuid primary key,
  job_id uuid not null references fast_generation_jobs(id) on delete cascade,
  segment_id uuid not null references fast_generation_segments(id) on delete cascade,
  sequence_no integer not null,
  question_type varchar(40) not null,
  difficulty varchar(16) not null,
  question_json text,
  review_json text not null default '{}',
  status varchar(24) not null,
  attempts integer not null default 0,
  import_status varchar(24) not null default 'NOT_IMPORTED',
  error_code varchar(80),
  error_message varchar(2000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(job_id, sequence_no),
  check (status in ('PLANNED','GENERATING','REVIEW_REQUIRED','FAILED')),
  check (import_status in ('NOT_IMPORTED','IMPORTED'))
);

create index if not exists idx_fast_generation_items_job on fast_generation_items(job_id, sequence_no);

create table if not exists question_normalization_jobs (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  original_name varchar(255) not null,
  media_type varchar(160) not null,
  source_storage_key varchar(700) not null,
  status varchar(24) not null,
  total_questions integer not null default 0,
  valid_questions integer not null default 0,
  error_questions integer not null default 0,
  preview_json text not null default '[]',
  error_message varchar(2000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  check (status in ('READY_FOR_REVIEW','IMPORTED','FAILED'))
);

create index if not exists idx_question_normalization_jobs_owner on question_normalization_jobs(owner_id, updated_at desc);

create table if not exists question_normalization_items (
  id uuid primary key,
  job_id uuid not null references question_normalization_jobs(id) on delete cascade,
  sequence_no integer not null,
  question_json text not null,
  status varchar(24) not null,
  error_code varchar(80),
  error_message varchar(2000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(job_id, sequence_no),
  check (status in ('VALID','INVALID'))
);

create index if not exists idx_question_normalization_items_job on question_normalization_items(job_id, sequence_no);
