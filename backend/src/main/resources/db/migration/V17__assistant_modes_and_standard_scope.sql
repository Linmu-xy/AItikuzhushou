alter table assistant_conversations add column if not exists mode varchar(32) not null default 'CHAT';
alter table assistant_conversations add column if not exists occupational_standard_id uuid references occupational_standards(id);

update assistant_conversations set mode='CHAT' where mode is null or trim(mode)='';

create index if not exists idx_assistant_conversations_owner_mode_updated
  on assistant_conversations(owner_id, mode, updated_at desc);
create index if not exists idx_assistant_conversations_standard
  on assistant_conversations(occupational_standard_id);
