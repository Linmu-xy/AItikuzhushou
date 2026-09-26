alter table app_users add column if not exists email varchar(254);
alter table app_users add column if not exists email_verified boolean not null default false;
alter table app_users add column if not exists email_verified_at timestamp with time zone;
create unique index if not exists uq_app_users_email on app_users(email);

create table if not exists email_verification_codes (
  id uuid primary key,
  email varchar(254) not null,
  purpose varchar(32) not null,
  code_hash varchar(255) not null,
  attempts integer not null default 0,
  consumed_at timestamp with time zone,
  expires_at timestamp with time zone not null,
  created_at timestamp with time zone not null
);
create index if not exists idx_email_codes_lookup on email_verification_codes(email, purpose, created_at desc);

create table if not exists model_provider_configs (
  config_key varchar(40) primary key,
  encrypted_api_key text,
  key_fingerprint varchar(32),
  text_model varchar(120) not null,
  vision_model varchar(120) not null,
  updated_by varchar(120) not null,
  updated_at timestamp with time zone not null
);
