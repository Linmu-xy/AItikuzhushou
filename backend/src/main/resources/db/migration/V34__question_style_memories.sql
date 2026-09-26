create table if not exists question_style_memories (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  level_name varchar(160) not null,
  question_type varchar(64) not null,
  signal_json text not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index if not exists idx_question_style_memories_lookup
  on question_style_memories(owner_id, level_name, question_type, updated_at desc);
