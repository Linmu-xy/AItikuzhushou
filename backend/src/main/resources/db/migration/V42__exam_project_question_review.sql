alter table exam_project_generation_items
  add column if not exists question_version integer not null default 1;

alter table exam_project_generation_items
  add column if not exists reviewer_id uuid references app_users(id);

alter table exam_project_generation_items
  add column if not exists review_comment varchar(2000);

alter table exam_project_generation_items
  add column if not exists reviewed_at timestamp with time zone;

create table if not exists exam_project_question_versions (
  id uuid primary key,
  generation_item_id uuid not null references exam_project_generation_items(id) on delete cascade,
  version integer not null,
  question_json text not null,
  design_json text not null,
  evidence_json text not null,
  change_type varchar(32) not null,
  change_summary varchar(2000),
  actor_id uuid references app_users(id),
  created_at timestamp with time zone not null,
  unique(generation_item_id, version),
  check (version > 0),
  check (change_type in ('AI_GENERATED','TEACHER_EDITED','STATUS_CHANGED','REOPENED'))
);

create table if not exists exam_project_question_review_events (
  id uuid primary key,
  generation_item_id uuid not null references exam_project_generation_items(id) on delete cascade,
  actor_id uuid references app_users(id),
  action varchar(32) not null,
  from_status varchar(32) not null,
  to_status varchar(32) not null,
  question_version integer not null,
  comment varchar(2000),
  snapshot_json text not null,
  created_at timestamp with time zone not null,
  check (action in ('EDITED','APPROVED','REJECTED','SAVED','REOPENED')),
  check (question_version > 0)
);

create index if not exists idx_exam_project_question_versions_item
  on exam_project_question_versions(generation_item_id, version desc);

create index if not exists idx_exam_project_question_review_events_item
  on exam_project_question_review_events(generation_item_id, created_at desc);
