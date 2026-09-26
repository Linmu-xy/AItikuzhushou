create table if not exists exam_project_evidence_snapshots (
  id uuid primary key,
  project_id uuid not null references exam_projects(id) on delete cascade,
  snapshot_version integer not null,
  status varchar(24) not null,
  blockers_json text not null,
  sources_json text not null,
  facts_json text not null,
  profiles_json text not null,
  snapshot_hash varchar(64) not null,
  created_by uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  check (status in ('READY','BLOCKED')),
  unique(project_id, snapshot_version)
);

create index if not exists idx_exam_project_snapshots_project
  on exam_project_evidence_snapshots(project_id, snapshot_version desc);
