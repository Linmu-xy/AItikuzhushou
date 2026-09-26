-- User-owned capacity is intentionally separate from the per knowledge-base display quota.
alter table app_users add column if not exists storage_quota_bytes bigint not null default 10737418240;

create table if not exists recycle_bin_items (
  id uuid primary key,
  owner_id uuid not null references app_users(id),
  resource_type varchar(32) not null,
  resource_id uuid not null,
  snapshot_json text not null,
  deleted_at timestamp with time zone not null,
  recoverable_until timestamp with time zone not null,
  restored_at timestamp with time zone
);
create index if not exists idx_recycle_bin_owner_active on recycle_bin_items(owner_id, recoverable_until desc);

create table if not exists knowledge_base_shares (
  id uuid primary key,
  knowledge_base_id uuid not null references knowledge_bases(id),
  owner_id uuid not null references app_users(id),
  share_token varchar(96) not null unique,
  expires_at timestamp with time zone not null,
  revoked_at timestamp with time zone,
  created_at timestamp with time zone not null
);
create index if not exists idx_kb_shares_token_active on knowledge_base_shares(share_token, expires_at);
