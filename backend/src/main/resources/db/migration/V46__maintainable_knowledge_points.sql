create table knowledge_points (
  id uuid primary key,
  knowledge_base_id uuid not null references knowledge_bases(id),
  title varchar(180) not null,
  chapter varchar(180) not null default '',
  description text not null,
  objective text not null,
  misconceptions text not null,
  status varchar(16) not null check(status in ('DRAFT','CONFIRMED','ARCHIVED')),
  origin varchar(16) not null check(origin in ('MANUAL','AI')),
  document_ids_json text not null default '[]',
  version integer not null default 1,
  updated_by uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
create index knowledge_points_base_status on knowledge_points(knowledge_base_id,status);
create table knowledge_point_versions (
  id uuid primary key,
  point_id uuid not null references knowledge_points(id),
  version integer not null,
  snapshot_json text not null,
  actor_id uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  unique(point_id,version)
);
