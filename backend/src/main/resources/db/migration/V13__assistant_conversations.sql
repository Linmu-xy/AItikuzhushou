create table if not exists assistant_conversations (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  knowledge_base_id uuid references knowledge_bases(id),
  title varchar(180) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
create index if not exists idx_assistant_conversations_owner_updated on assistant_conversations(owner_id, updated_at desc);

create table if not exists assistant_messages (
  id uuid primary key,
  conversation_id uuid not null references assistant_conversations(id) on delete cascade,
  message_role varchar(16) not null,
  content text not null,
  citation_json text,
  generic_answer boolean not null default false,
  pending_action_json text,
  created_at timestamp with time zone not null
);
create index if not exists idx_assistant_messages_conversation_created on assistant_messages(conversation_id, created_at);
