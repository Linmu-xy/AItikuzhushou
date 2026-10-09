create table if not exists user_question_banks (
  id uuid primary key,
  owner_id uuid not null references app_users(id) on delete cascade,
  name varchar(100) not null,
  description varchar(500) not null default '',
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);

create index if not exists idx_user_question_banks_owner
  on user_question_banks(owner_id, updated_at desc);

create table if not exists user_question_bank_entries (
  id uuid primary key,
  bank_id uuid not null references user_question_banks(id) on delete cascade,
  entry_type varchar(16) not null,
  entry_key varchar(180) not null,
  source_type varchar(16) not null,
  source_id uuid not null,
  source_run_id uuid,
  variant_no integer,
  sequence_no integer not null default 0,
  title varchar(500) not null default '',
  snapshot_json text not null,
  source_version integer not null default 1,
  created_at timestamp with time zone not null,
  check (entry_type in ('QUESTION','PAPER')),
  check (source_type in ('PROJECT','LEGACY_JOB')),
  unique(bank_id, entry_key)
);

create index if not exists idx_user_question_bank_entries_bank
  on user_question_bank_entries(bank_id, entry_type, sequence_no, created_at);
