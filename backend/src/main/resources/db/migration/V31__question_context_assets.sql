create table if not exists question_context_assets (
  id uuid primary key,
  knowledge_base_id uuid not null references knowledge_bases(id) on delete cascade,
  source_document_id uuid references source_documents(id) on delete set null,
  asset_type varchar(32) not null,
  title varchar(240) not null,
  source_organization varchar(240),
  source_url varchar(1200),
  license_status varchar(32) not null,
  profession varchar(160),
  occupational_level varchar(120),
  region varchar(160),
  published_at timestamp with time zone,
  valid_from timestamp with time zone,
  valid_until timestamp with time zone,
  trust_level varchar(24) not null,
  content text not null,
  content_hash varchar(64) not null,
  status varchar(24) not null,
  created_by uuid not null references app_users(id),
  approved_by uuid references app_users(id),
  approved_at timestamp with time zone,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  check (asset_type in ('CONTEXT','EXAM_PATTERN','TEMPORAL_CONTEXT')),
  check (status in ('PENDING','APPROVED','REJECTED','EXPIRED')),
  check (trust_level in ('OFFICIAL','INDUSTRY','ENTERPRISE','USER_SUPPLIED')),
  check (license_status in ('OWNED','LICENSED','PUBLIC_OFFICIAL','INTERNAL','UNKNOWN'))
);

create index if not exists idx_question_context_assets_lookup
  on question_context_assets(knowledge_base_id, status, asset_type, valid_until, occupational_level);

create table if not exists question_evidence_snapshots (
  id uuid primary key,
  job_id uuid not null references generation_jobs(id) on delete cascade,
  sequence_no integer not null,
  standard_evidence_json text not null,
  context_assets_json text not null,
  snapshot_hash varchar(64) not null,
  created_at timestamp with time zone not null,
  unique(job_id, sequence_no)
);

create index if not exists idx_question_evidence_snapshots_job
  on question_evidence_snapshots(job_id, sequence_no);
