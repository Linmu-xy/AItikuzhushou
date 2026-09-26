alter table assistant_messages add column if not exists answer_blocks_json text;
alter table assistant_messages add column if not exists model_output_json text;
alter table assistant_messages add column if not exists status_code varchar(64);

create index if not exists idx_assistant_messages_status_created
  on assistant_messages(status_code, created_at desc);
