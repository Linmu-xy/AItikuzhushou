alter table generation_jobs add column if not exists owner_id uuid references app_users(id);
create index if not exists idx_generation_jobs_owner_created on generation_jobs(owner_id,created_at);
