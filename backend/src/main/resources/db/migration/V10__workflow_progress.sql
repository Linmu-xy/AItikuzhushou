alter table generation_jobs add column if not exists stage_code varchar(64);
alter table generation_jobs add column if not exists processed_items integer not null default 0;
alter table generation_jobs add column if not exists total_items integer not null default 0;
alter table generation_jobs add column if not exists status_message varchar(500);
alter table generation_jobs add column if not exists error_code varchar(64);
alter table generation_jobs add column if not exists idempotency_key varchar(120);
create unique index if not exists uq_generation_jobs_owner_idempotency on generation_jobs(owner_id,idempotency_key);

create table if not exists workflow_tasks (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  task_type varchar(48) not null,
  resource_id uuid,
  status varchar(24) not null,
  stage_code varchar(64) not null,
  progress integer not null,
  processed_items integer not null,
  total_items integer not null,
  status_message varchar(500),
  error_code varchar(64),
  error_message varchar(2000),
  idempotency_key varchar(120) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(owner_id,idempotency_key)
);
create index if not exists idx_workflow_tasks_owner_updated on workflow_tasks(owner_id,updated_at desc);
create index if not exists idx_workflow_tasks_status on workflow_tasks(status,updated_at);
