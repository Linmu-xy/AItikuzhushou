create table if not exists question_professional_audits (
  id uuid primary key,
  job_id uuid not null references generation_jobs(id) on delete cascade,
  sequence_no integer not null,
  generation_attempt integer not null,
  stage varchar(32) not null,
  passed boolean not null,
  discrimination_score integer not null,
  flags_json text not null default '[]',
  feedback text,
  design_json text,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(job_id, sequence_no, generation_attempt, stage)
);

create index if not exists idx_question_professional_audits_job_sequence
  on question_professional_audits(job_id, sequence_no, created_at desc);
