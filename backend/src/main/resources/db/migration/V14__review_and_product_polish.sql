alter table source_documents add column if not exists content_sha256 varchar(64);
alter table source_documents add column if not exists parse_quality double precision;
alter table source_documents add column if not exists parse_warnings text;
create index if not exists idx_source_documents_kb_hash on source_documents(knowledge_base_id,content_sha256);

create table if not exists question_reviews (
  id uuid primary key,
  job_id uuid not null references generation_jobs(id) on delete cascade,
  sequence_no integer not null,
  owner_id uuid references app_users(id),
  reviewer_id uuid references app_users(id),
  review_status varchar(32) not null default 'AI_DRAFT',
  locked boolean not null default false,
  comment text,
  question_json text not null,
  version integer not null default 1,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(job_id,sequence_no)
);
create index if not exists idx_question_reviews_job_status on question_reviews(job_id,review_status,sequence_no);

create table if not exists question_review_events (
  id uuid primary key,
  review_id uuid not null references question_reviews(id) on delete cascade,
  actor_id uuid references app_users(id),
  action varchar(48) not null,
  comment text,
  snapshot_json text not null,
  created_at timestamp with time zone not null
);
create index if not exists idx_question_review_events_review on question_review_events(review_id,created_at desc);

alter table generation_jobs add column if not exists prompt_version varchar(48) default 'question-v2';
alter table generation_jobs add column if not exists model_name varchar(100);
