create table if not exists exam_projects (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  knowledge_base_id uuid not null references knowledge_bases(id),
  name varchar(180) not null,
  mode varchar(24) not null,
  status varchar(32) not null,
  requirement_text text,
  variant_count integer not null default 1,
  difficulty_profile_json text not null,
  scoring_structure_json text not null,
  authorization_confirmed boolean not null default false,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
create index if not exists idx_exam_projects_owner_updated on exam_projects(owner_id, updated_at desc);
create index if not exists idx_exam_projects_kb_updated on exam_projects(knowledge_base_id, updated_at desc);

create table if not exists exam_project_sources (
  id uuid primary key,
  project_id uuid not null references exam_projects(id) on delete cascade,
  source_type varchar(24) not null,
  source_document_id uuid references source_documents(id) on delete cascade,
  cad_material_id uuid references cad_materials(id) on delete cascade,
  source_role varchar(32) not null,
  version_ref varchar(128),
  enabled boolean not null default true,
  created_at timestamp with time zone not null,
  constraint ck_exam_project_source_one_ref check (
    (source_document_id is not null and cad_material_id is null)
    or (source_document_id is null and cad_material_id is not null)
  )
);
create unique index if not exists uq_exam_project_document_role on exam_project_sources(project_id, source_document_id, source_role);
create unique index if not exists uq_exam_project_cad_role on exam_project_sources(project_id, cad_material_id, source_role);
create index if not exists idx_exam_project_sources_project on exam_project_sources(project_id, created_at);
