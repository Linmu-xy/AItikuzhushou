create table if not exists question_generation_designs (
  id uuid primary key,
  job_id uuid not null references generation_jobs(id) on delete cascade,
  sequence_no integer not null,
  generation_attempt integer not null,
  stage varchar(32) not null,
  model varchar(120) not null,
  reasoning_effort varchar(16) not null,
  design_json text not null,
  validation_json text not null,
  duration_ms bigint not null,
  created_at timestamp with time zone not null,
  unique(job_id, sequence_no, generation_attempt, stage)
);

create index if not exists idx_question_generation_designs_job_sequence
  on question_generation_designs(job_id, sequence_no, generation_attempt desc);
