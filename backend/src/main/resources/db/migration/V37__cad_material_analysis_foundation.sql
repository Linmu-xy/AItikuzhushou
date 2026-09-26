create table if not exists cad_materials (
  id uuid primary key,
  knowledge_base_id uuid not null references knowledge_bases(id),
  original_name varchar(255) not null,
  media_type varchar(120),
  format varchar(24) not null,
  storage_key varchar(500) not null,
  size_bytes bigint not null,
  content_sha256 varchar(64) not null,
  status varchar(32) not null,
  created_by uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
create unique index if not exists uq_cad_materials_kb_hash on cad_materials(knowledge_base_id, content_sha256);
create index if not exists idx_cad_materials_kb_created on cad_materials(knowledge_base_id, created_at desc);

create table if not exists cad_analysis_jobs (
  id uuid primary key,
  material_id uuid not null references cad_materials(id) on delete cascade,
  workflow_task_id uuid references workflow_tasks(id) on delete set null,
  parser varchar(80),
  status varchar(32) not null,
  result_json text,
  analysis_storage_key varchar(500),
  preview_storage_key varchar(500),
  error_code varchar(64),
  error_message varchar(2000),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
create unique index if not exists uq_cad_analysis_jobs_task on cad_analysis_jobs(workflow_task_id);
create index if not exists idx_cad_analysis_jobs_material on cad_analysis_jobs(material_id, created_at desc);

create table if not exists cad_facts (
  id uuid primary key,
  material_id uuid not null references cad_materials(id) on delete cascade,
  analysis_job_id uuid not null references cad_analysis_jobs(id) on delete cascade,
  fact_name varchar(120) not null,
  value_json text not null,
  unit varchar(32),
  source_ref varchar(500),
  confidence double precision not null,
  verified boolean not null default false,
  usable_for_generation boolean not null default false,
  verification_note varchar(1000),
  verified_by uuid references app_users(id),
  verified_at timestamp with time zone,
  created_at timestamp with time zone not null
);
create index if not exists idx_cad_facts_material on cad_facts(material_id, fact_name);
create index if not exists idx_cad_facts_generation on cad_facts(material_id, usable_for_generation, verified);

create table if not exists cad_annotations (
  id uuid primary key,
  material_id uuid not null references cad_materials(id) on delete cascade,
  analysis_job_id uuid not null references cad_analysis_jobs(id) on delete cascade,
  annotation_kind varchar(64) not null,
  page_number integer,
  value_json text not null,
  source_ref varchar(500),
  confidence double precision not null,
  verified boolean not null default false,
  usable_for_generation boolean not null default false,
  verification_note varchar(1000),
  verified_by uuid references app_users(id),
  verified_at timestamp with time zone,
  created_at timestamp with time zone not null
);
create index if not exists idx_cad_annotations_material on cad_annotations(material_id, page_number, annotation_kind);

create table if not exists cad_preview_assets (
  id uuid primary key,
  material_id uuid not null references cad_materials(id) on delete cascade,
  analysis_job_id uuid not null references cad_analysis_jobs(id) on delete cascade,
  asset_type varchar(32) not null,
  media_type varchar(120) not null,
  storage_key varchar(500) not null,
  size_bytes bigint not null,
  created_at timestamp with time zone not null
);
