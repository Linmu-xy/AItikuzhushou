create table if not exists exam_project_generation_tasks (
  id uuid primary key,
  project_id uuid not null references exam_projects(id) on delete cascade,
  evidence_snapshot_id uuid not null references exam_project_evidence_snapshots(id),
  status varchar(24) not null,
  variant_count integer not null,
  question_count_per_variant integer not null,
  total_question_count integer not null,
  request_json text not null,
  created_by uuid not null references app_users(id),
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  check (status in ('READY','SUPERSEDED')),
  check (variant_count between 1 and 20),
  check (question_count_per_variant > 0),
  check (total_question_count > 0)
);

create table if not exists exam_project_variant_items (
  id uuid primary key,
  generation_task_id uuid not null references exam_project_generation_tasks(id) on delete cascade,
  variant_no integer not null,
  variant_label varchar(16) not null,
  sequence_no integer not null,
  question_type varchar(80) not null,
  type_label varchar(160) not null,
  difficulty varchar(16) not null,
  points integer not null,
  source_roles_json text not null,
  status varchar(24) not null default 'PLANNED',
  created_at timestamp with time zone not null,
  check (variant_no between 1 and 20),
  check (sequence_no > 0),
  check (difficulty in ('EASY','MEDIUM','HARD')),
  check (points > 0),
  check (status in ('PLANNED','GENERATED','REVIEWED')),
  unique(generation_task_id, variant_no, sequence_no)
);

create index if not exists idx_exam_project_generation_tasks_project
  on exam_project_generation_tasks(project_id, created_at desc);

create index if not exists idx_exam_project_generation_tasks_snapshot
  on exam_project_generation_tasks(evidence_snapshot_id);

create index if not exists idx_exam_project_variant_items_task
  on exam_project_variant_items(generation_task_id, variant_no, sequence_no);
