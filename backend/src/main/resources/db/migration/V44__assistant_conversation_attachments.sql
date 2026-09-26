create table if not exists assistant_attachments (
  id uuid primary key,
  conversation_id uuid not null references assistant_conversations(id) on delete cascade,
  owner_id uuid not null references app_users(id),
  original_name varchar(255) not null,
  media_type varchar(128) not null,
  size_bytes bigint not null,
  extracted_text text not null,
  truncated boolean not null default false,
  created_at timestamp with time zone not null
);
create index if not exists idx_assistant_attachments_conversation on assistant_attachments(conversation_id, created_at);
