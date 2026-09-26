alter table model_usage_daily add column if not exists input_tokens bigint not null default 0;
alter table model_usage_daily add column if not exists output_tokens bigint not null default 0;
alter table model_usage_daily add column if not exists reasoning_tokens bigint not null default 0;

create table if not exists model_usage_events (
  id uuid primary key,
  user_id uuid not null references app_users(id) on delete cascade,
  usage_date date not null,
  model varchar(120) not null,
  reasoning_effort varchar(16) not null,
  thinking_enabled boolean not null,
  input_chars bigint not null default 0,
  output_chars bigint not null default 0,
  prompt_tokens bigint not null default 0,
  completion_tokens bigint not null default 0,
  reasoning_tokens bigint not null default 0,
  duration_ms bigint not null default 0,
  created_at timestamp with time zone not null
);

create index if not exists idx_model_usage_events_user_date on model_usage_events(user_id, usage_date, created_at);
create index if not exists idx_model_usage_events_model on model_usage_events(model, usage_date);
